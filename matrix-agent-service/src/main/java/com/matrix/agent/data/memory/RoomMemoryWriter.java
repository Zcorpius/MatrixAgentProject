package com.matrix.agent.data.memory;

import android.util.Log;

import java.util.regex.Pattern;

import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.task.EpisodicSummary;
import com.matrix.agent.data.memory.MemoryWriter;
import com.matrix.agent.task.Trajectory;
import com.matrix.agent.task.identity.ActorUsers;
import com.matrix.agent.task.identity.AgentRequest;
import com.matrix.agent.data.memory.MemoryLayer;
import com.matrix.agent.data.db.MemoryRecordDao;
import com.matrix.agent.data.db.MemoryRecordEntity;
import com.matrix.agent.data.db.SessionHistoryDao;
import com.matrix.agent.data.db.SessionHistoryEntity;

/**
 * Room-backed MemoryWriter 实现。
 *
 * <p>持 {@link SessionHistoryDao} / {@link MemoryRecordDao} / {@link EpisodicMemorySourceImpl}
 * (均可空,fail-log)。database=null 时由 {@link com.matrix.agent.app.AppContainer} 装配
 * {@link MemoryWriter#NOOP},本类不被实例化。
 *
 * <p><b>fail-log</b>:所有写入路径 try/catch 包裹,异常仅 Log.w,不向上传播。
 * 与 RoomAuditRepository fail-open 语义一致,但 Memory 不阻塞主路径。
 *
 * <p><b>缓存失效</b>:writeEpisodicOnTerminal 写入后必须调 {@link EpisodicMemorySourceImpl#invalidateCache()}
 * 失效 5min LRU——否则"任务结束 → 立即下一任务召回"读到旧值(注释明确)。
 * SemanticMemorySourceImpl 无缓存(直查 Dao),不需要失效。
 *
 * <p><b>epoch 原子性</b>:每个写入接口(episodic / semantic)都在
 * {@link RoomMemoryStore.TransactionRunner#runInTransaction(Runnable)} 内完成
 * "读 __system__ epoch + 比较 + 写入"。生产装配传 {@code database::runInTransaction}
 * (Room + SQLite 单写者锁保证与 clearUserDataAndBump 的事务序列化执行),
 * 测试传 {@code Runnable::run}。
 * 用户硬约束:不能只在 Java 内存比较 epoch——必须与数据库写操作同事务。
 *
 * <p>epoch 直接从 memory_record 的 __system__/__system__/preference/__epoch__ 行 SELECT,
 * 不走 RoomMemoryStore.epoch AtomicLong 缓存——数据库是单一权威,跨进程重启不丢。
 *
 * <p><b>调用方契约(尚无 enforcement)</b>:{@code writeSemantic / readSemantic}
 * 的 {@code userId / zone} 参数由调用方传入,本类不做"调用方身份与参数一致"校验。当前唯一调用方
 * {@code MockCapabilityProvider.MemorySemanticSaveHandler} 用 {@code ActorUsers.userIdOf(request)}
 * + {@code request.getOccupantZone().wireValue()} 推导,无实际越权路径。但未来真实 AAOS Provider
 * 或第三方插件若误传其他用户 / zone 的值,可能写入错误访问域。后续版本引入 {@code CallerContext}
 * (PolicyEngine 已持有的 request.userId / zone 透传到 Writer 入口)做 enforcement;仅在此
 * 标注契约——调用方必须传"当前 request 的 userId / zone"。
 */
public final class RoomMemoryWriter implements MemoryWriter {
    private static final String TAG = "MatrixAgent";

    /**
     * writer-side defence-in-depth——与 CapabilityRegistry.memory.semantic.save
     * 的 schema pattern 同款。Schema 已强制,但即便 Provider 漏过(未来真实 AAOS Provider /
     * 第三方实现 / 测试桩直接调 writer),Writer 仍 fail-closed 拒绝。
     *
     * <p>{@code SEMANTIC_VALUE_MAX_LEN = 2048} 是 Java char(UTF-16 code unit)数,
     * <b>不是</b> UTF-8 字节数。中文 / emoji 等 UTF-8 实际字节数最多 ~4 倍(最坏 8KB/行,
     * SQLite TEXT 无压力)。文档/UI 描述必须用"最多 2048 字符",不要用"≤ 2KB"——后者误导。
     * UTF-8 字节上限留后续版本视真实 PII 容量需求评估。
     */
    private static final Pattern SEMANTIC_KEY_PATTERN =
            Pattern.compile("^(family|allergy|work|fact)\\.[A-Za-z0-9_.]+$");
    private static final int SEMANTIC_KEY_MAX_LEN = 64;
    private static final int SEMANTIC_VALUE_MAX_LEN = 2048;  // chars (UTF-16 code units), not UTF-8 bytes
    private static final int SOURCE_SESSION_ID_MAX_LEN = 128;

    private final SessionHistoryDao sessionHistoryDao;
    private final MemoryRecordDao memoryRecordDao;
    private final EpisodicMemorySourceImpl episodicSource;
    private final RoomMemoryStore.TransactionRunner transactionRunner;

    /**
     * @param transactionRunner 生产传 {@code database::runInTransaction}
     *     (Room 内部 SQLite 单写者锁序列化);测试传 {@code Runnable::run}
     */
    public RoomMemoryWriter(SessionHistoryDao sessionHistoryDao, MemoryRecordDao memoryRecordDao,
            EpisodicMemorySourceImpl episodicSource,
            RoomMemoryStore.TransactionRunner transactionRunner) {
        this.sessionHistoryDao = sessionHistoryDao;
        this.memoryRecordDao = memoryRecordDao;
        this.episodicSource = episodicSource;
        this.transactionRunner = transactionRunner;
    }

    @Override
    public void writeEpisodicOnTerminal(AgentRequest request, AgentOutcome outcome, long requestEpoch) {
        if (transactionRunner == null || sessionHistoryDao == null
                || request == null || outcome == null) return;
        // 终态过滤在 EpisodicSummary 内做——CANCELLED /
        // TIMED_OUT / PREEMPTED / REJECTED / PROTOCOL_ERROR / EXECUTION_UNKNOWN / DEFERRED
        // / PARTIALLY_SUCCEEDED 全部 skip。用户指定:仅 SUCCEEDED / FAILED 写入。
        // 注意:skip 短路在事务**之前**——不浪费 Room 单写者锁。
        EpisodicSummary summary = EpisodicSummary.build(request, outcome);
        if (summary.shouldSkip()) {
            Log.i(TAG, "[MemoryWriter] skip episodic write req=" + outcome.getRequestId()
                    + " state=" + outcome.getFinalState()
                    + " (only SUCCEEDED/FAILED persisted)");
            return;
        }
        try {
            // 顺手优化:written flag 区分"真写入"与"stale / fail-closed reject",
            // 后者不再触发 invalidateCache(避免无意义 cache miss)。同模式:ok[0]。
            boolean[] written = {false};
            transactionRunner.runInTransaction(() -> {
                Long currentEpochBoxed = readEpochFromSystemRow();
                if (currentEpochBoxed == null) {
                    Log.w(TAG, "[MemoryWriter] reject episodic write req=" + outcome.getRequestId()
                            + " reason=epoch_read_failed (fail-closed)");
                    return;  // epoch 读取失败 → 事务内 return,不写
                }
                long currentEpoch = currentEpochBoxed.longValue();
                if (requestEpoch != currentEpoch) {
                    Log.w(TAG, "[MemoryWriter] reject stale episodic write req=" + outcome.getRequestId()
                            + " requestEpoch=" + requestEpoch + " currentEpoch=" + currentEpoch
                            + " (clearUserData 已发生,在途写入被事务内拒绝)");
                    return;  // 事务内 return,不写
                }
                Trajectory trajectory = outcome.getTrajectory();
                SessionHistoryEntity row = new SessionHistoryEntity();
                row.userId = ActorUsers.userIdOf(request);
                row.zone = request.getOccupantZone() == null ? "" : request.getOccupantZone().name();
                row.sessionId = request.getSessionId();
                row.startedAtMillis = summary.getStartedAtMillis();
                row.actor = request.getActor() == null ? "" : request.getActor().name();
                row.finalState = summary.getFinalState();
                row.stopReason = outcome.getStopReason() == null ? "" : outcome.getStopReason().name();
                row.durationMs = summary.getDurationMs();
                row.turnCount = summary.getTurnCount();
                // trajectoryJson 列名保留(不改 schema),内容从完整 trajectory JSON
                // 换成 EpisodicSummary JSON——仅 startedAt/finalState/durationMs/turnCount/
                // successfulCapabilities ≤3,**不含 userText/assistantContent/arguments/result**。
                row.trajectoryJson = summary.toJson();
                sessionHistoryDao.insert(row);  // @Insert(REPLACE) 幂等
                written[0] = true;
            });
            // 仅在真写入后失效缓存——stale reject / fail-closed reject 都不触发 invalidate
            if (written[0] && episodicSource != null) {
                episodicSource.invalidateCache();  // 失效 5min LRU
            }
            if (written[0]) {
                Log.i(TAG, "[MemoryWriter] episodic write OK req=" + outcome.getRequestId()
                        + " state=" + outcome.getFinalState()
                        + " requestEpoch=" + requestEpoch
                        + " summaryBytes=" + summary.toJson().length());
            } else {
                Log.i(TAG, "[MemoryWriter] episodic write skipped req=" + outcome.getRequestId()
                        + " (stale / fail-closed / row-missing mismatch)");
            }
        } catch (Exception ex) {
            Log.w(TAG, "[MemoryWriter] episodic write FAILED req="
                    + (outcome != null ? outcome.getRequestId() : "null")
                    + " cause=" + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    /**
     * 契约:调用方必须传"当前 request 的 userId / zone"——本方法不做"调用方身份
     * 与参数一致"校验。MockCapabilityProvider.MemorySemanticSaveHandler 推导正确无越权路径;
     * 第三方 Provider 误传可串数据(后续版本加 CallerContext enforcement)。
     */
    @Override
    public boolean writeSemantic(String userId, String zone, String key, String value,
            double score, String sourceSessionId, long requestEpoch) {
        if (transactionRunner == null || memoryRecordDao == null
                || userId == null || zone == null || key == null) {
            return false;
        }
        // writer-side defence-in-depth。Schema 已强制 pattern/maxLength,
        // 但 Provider / 测试桩可能绕过 PolicyEngine schema 校验直接调 writer,这层仍 fail-closed。
        // 用户硬约束:"在 Schema / Policy / Writer 至少一层强制"。
        if (!isAcceptableSemanticKey(key) || !isAcceptableSemanticValue(value)
                || !isAcceptableScore(score) || !isAcceptableSourceSessionId(sourceSessionId)) {
            Log.w(TAG, "[MemoryWriter] reject semantic write by validation"
                    + " keyLen=" + key.length()
                    + " valueLen=" + (value == null ? -1 : value.length())
                    + " score=" + score
                    + " sessionIdLen=" + (sourceSessionId == null ? -1 : sourceSessionId.length()));
            return false;
        }
        try {
            boolean[] ok = {false};
            transactionRunner.runInTransaction(() -> {
                Long currentEpochBoxed = readEpochFromSystemRow();
                if (currentEpochBoxed == null) {
                    Log.w(TAG, "[MemoryWriter] reject semantic write key=" + key
                            + " reason=epoch_read_failed (fail-closed)");
                    return;  // epoch 读取失败 → 事务内 return,ok[0] 仍为 false
                }
                long currentEpoch = currentEpochBoxed.longValue();
                if (requestEpoch != currentEpoch) {
                    Log.w(TAG, "[MemoryWriter] reject stale semantic write key=" + key
                            + " requestEpoch=" + requestEpoch + " currentEpoch=" + currentEpoch
                            + " (clearUserData 已发生,在途写入被事务内拒绝)");
                    return;
                }
                MemoryRecordEntity row = new MemoryRecordEntity();
                row.userId = userId;
                row.zone = zone;
                row.layer = MemoryLayer.SEMANTIC.wireValue();
                row.key = key;
                row.value = value;
                row.score = score;
                row.capturedAtMs = System.currentTimeMillis();
                row.sourceSessionId = sourceSessionId;
                memoryRecordDao.upsert(row);  // REPLACE 幂等
                ok[0] = true;
            });
            if (ok[0]) {
                Log.i(TAG, "[MemoryWriter] semantic write OK key=" + key
                        + " user=" + userId + " requestEpoch=" + requestEpoch);
            }
            return ok[0];
        } catch (Exception ex) {
            Log.w(TAG, "[MemoryWriter] semantic write FAILED key=" + key
                    + " cause=" + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return false;
        }
    }

    /**
     * 契约:同 {@link #writeSemantic}——调用方必须传当前 request 的 userId / zone。
     */
    @Override
    public String readSemantic(String userId, String zone, String key) {
        if (memoryRecordDao == null || userId == null || zone == null || key == null) return null;
        try {
            MemoryRecordEntity row = memoryRecordDao.queryByKey(userId, zone,
                    MemoryLayer.SEMANTIC.wireValue(), key);
            return row == null ? null : row.value;
        } catch (Exception ex) {
            Log.w(TAG, "[MemoryWriter] semantic read FAILED key=" + key
                    + " cause=" + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return null;
        }
    }

    /**
     * 从 memory_record 表的 __system__/__system__/preference/__epoch__ 行读 currentEpoch。
     *
     * <p>不走 RoomMemoryStore.epoch AtomicLong 缓存——保证数据库是单一权威,
     * 与 clearUserDataAndBump 在事务内写入的 epoch 行同源。
     *
     * <p>fail-closed 语义。用户硬约束:"系统 epoch 行不存在与查询失败不能用同一个
     * 0L 表示... 写入路径遵循 fail-closed"。返回值含义:
     * <ul>
     *   <li>row 存在 + value 合法 long → 返回包装值</li>
     *   <li>row 不存在 / value null → 返回 {@code Long.valueOf(0L)}(合法初始 epoch,
     *       与 RoomMemoryStore.loadEpochFromRow 同语义,等价于历史版本行为)</li>
     *   <li>DAO 异常 / Long.parseLong 失败 / 事务异常 / memoryRecordDao==null →
     *       {@code null}(调用方必须拒绝写入,事务内 return,不持久化)</li>
     * </ul>
     */
    private Long readEpochFromSystemRow() {
        if (memoryRecordDao == null) return null;  // DAO 不可用 → fail-closed
        try {
            MemoryRecordEntity row = memoryRecordDao.queryByKey(
                    RoomMemoryStore.SYSTEM_USER, RoomMemoryStore.SYSTEM_ZONE,
                    RoomMemoryStore.PREFERENCE_LAYER, RoomMemoryStore.EPOCH_KEY);
            if (row == null || row.value == null) return 0L;  // 合法初始
            return Long.parseLong(row.value);
        } catch (Exception ex) {
            Log.w(TAG, "[MemoryWriter] readEpochFromSystemRow FAILED, fail-closed reject cause="
                    + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return null;  // 不再 0L 兜底——把"读取失败"伪装成"epoch=0"会让 stale 写入通过
        }
    }

    // writer-side validation helpers。与 CapabilityRegistry.memory.semantic.save
    // schema 同款约束。Schema 已强制,但 writer 仍兜底防 Provider 漏检 / 第三方 Provider / 测试桩。

    private static boolean isAcceptableSemanticKey(String key) {
        if (key.isEmpty() || key.length() > SEMANTIC_KEY_MAX_LEN) return false;
        return SEMANTIC_KEY_PATTERN.matcher(key).matches();
    }

    private static boolean isAcceptableSemanticValue(String value) {
        return value != null && !value.isEmpty() && value.length() <= SEMANTIC_VALUE_MAX_LEN;
    }

    private static boolean isAcceptableScore(double score) {
        return Double.isFinite(score) && score >= 0.0 && score <= 1.0;
    }

    private static boolean isAcceptableSourceSessionId(String sessionId) {
        if (sessionId == null) return true;  // 历史数据允许 null
        return sessionId.length() <= SOURCE_SESSION_ID_MAX_LEN;
    }
}
