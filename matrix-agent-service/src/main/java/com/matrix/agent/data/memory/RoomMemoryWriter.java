package com.matrix.agent.data.memory;


import com.matrix.agent.identity.VehicleZone;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.identity.ActorUsers;

import android.util.Log;

import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

import com.matrix.agent.data.db.MemoryRecordDao;
import com.matrix.agent.data.db.MemoryRecordEntity;
import com.matrix.agent.data.db.SessionHistoryDao;
import com.matrix.agent.data.db.SessionHistoryEntity;

/**
 * Room-backed MemoryWriter 实现。
 *
 * <p>持 {@link SessionHistoryDao} / {@link MemoryRecordDao} / {@link EpisodicMemorySourceImpl}
 * (均可空,fail-log)。database=null 时由 {@link com.matrix.agent.host.di.AppContainer} 装配
 * {@link MemoryWriter#NOOP},本类不被实例化。
 *
 * <p><b>fail-log</b>:所有写入路径 try/catch 包裹,异常仅 Log.w,不向上传播。
 * 与 RoomAuditRepository fail-open 语义一致,但 Memory 不阻塞主路径。
 *
 * <p>Episodic and Semantic recall read through the DAO without a local result cache, so clear
 * and per-key delete are visible on the next lookup.
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
 * <p>Semantic operations derive userId, zone, session and epoch inside this Writer from the
 * bound AgentRequest. A Provider cannot supply a different owner string.
 */
public final class RoomMemoryWriter implements MemoryWriter {
    private static final String TAG = "MatrixAgent";

    /**
     * Writer-side validation uses {@link MemoryKeyCatalog} for the same key contract as
     * schema, Policy and prompt projection.
     *
     * <p>{@code SEMANTIC_VALUE_MAX_LEN = 2048} 是 Java char(UTF-16 code unit)数,
     * <b>不是</b> UTF-8 字节数。中文 / emoji 等 UTF-8 实际字节数最多 ~4 倍(最坏 8KB/行,
     * SQLite TEXT 无压力)。文档/UI 描述必须用"最多 2048 字符",不要用"≤ 2KB"——后者误导。
     * UTF-8 字节上限留后续版本视真实 PII 容量需求评估。
     */
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
    public void writeEpisodic(AgentRequest request, EpisodicWrite write) {
        String rejection = episodicRejection(request, write);
        if (rejection != null) {
            Log.w(TAG, "[MemoryWriter] episodic rejected reason=" + rejection);
            return;
        }
        VehicleZone canonicalZone = request.getOccupantZone();
        // task 侧适配器已经完成终态过滤（仅 SUCCEEDED/FAILED）和安全摘要构建。
        // 本层只承担事务内 epoch gate、实体写入与成功后的缓存失效。
        try {
            // 顺手优化:written flag 区分"真写入"与"stale / fail-closed reject",
            // 后者不再触发 invalidateCache(避免无意义 cache miss)。同模式:ok[0]。
            boolean[] written = {false};
            transactionRunner.runInTransaction(() -> {
                Long currentEpochBoxed = readEpochFromSystemRow();
                if (currentEpochBoxed == null) {
                    Log.w(TAG, "[MemoryWriter] reject episodic write req=" + write.requestId
                            + " reason=epoch_read_failed (fail-closed)");
                    return;  // epoch 读取失败 → 事务内 return,不写
                }
                long currentEpoch = currentEpochBoxed.longValue();
                if (write.requestEpoch != currentEpoch) {
                    Log.w(TAG, "[MemoryWriter] reject stale episodic write req=" + write.requestId
                            + " requestEpoch=" + write.requestEpoch + " currentEpoch=" + currentEpoch
                            + " (clearUserData 已发生,在途写入被事务内拒绝)");
                    return;  // 事务内 return,不写
                }
                SessionHistoryEntity row = new SessionHistoryEntity();
                row.userId = write.userId;
                row.zone = canonicalZone.wireValue();
                row.sessionId = write.sessionId;
                row.startedAtMillis = write.startedAtMillis;
                row.actor = write.actor;
                row.finalState = write.finalState;
                row.stopReason = write.stopReason;
                row.durationMs = write.durationMs;
                row.turnCount = write.turnCount;
                // Column name is retained for compatibility. Payload is a versioned,
                // bounded event summary; v2 includes up to three verified facts.
                row.trajectoryJson = write.summaryJson;
                sessionHistoryDao.insert(row);  // @Insert(REPLACE) 幂等
                sessionHistoryDao.deleteOlderThan(write.userId, canonicalZone.wireValue(),
                        System.currentTimeMillis() - 30L * 24L * 60L * 60L * 1000L);
                sessionHistoryDao.retainLatest(write.userId, canonicalZone.wireValue(), 100);
                written[0] = true;
            });
            // Retained for compatibility with the source's invalidation hook.
            if (written[0] && episodicSource != null) {
                episodicSource.invalidateCache();
            }
            if (written[0]) {
                Log.i(TAG, "[MemoryWriter] episodic write OK req=" + write.requestId
                        + " state=" + write.finalState
                        + " requestEpoch=" + write.requestEpoch
                        + " summaryBytes=" + write.summaryJson.getBytes(
                                java.nio.charset.StandardCharsets.UTF_8).length);
            } else {
                Log.i(TAG, "[MemoryWriter] episodic write skipped req=" + write.requestId
                        + " (stale / fail-closed / row-missing mismatch)");
            }
        } catch (Exception ex) {
            Log.w(TAG, "[MemoryWriter] episodic write FAILED req="
                    + (write != null ? write.requestId : "null")
                    + " cause=" + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    /** First failed field only: no identifier, payload or exception message is logged. */
    private String episodicRejection(AgentRequest request, EpisodicWrite write) {
        if (transactionRunner == null || sessionHistoryDao == null) return "storage_unavailable";
        if (request == null || request.getActor() == null) return "request_missing";
        if (request.getOccupantZone() == null) return "zone_missing";
        if (write == null) return "write_missing";
        if (VehicleZone.parse(write.zone) != request.getOccupantZone()) return "zone_mismatch";
        if (!ActorUsers.userIdOf(request).equals(write.userId)) return "owner_mismatch";
        if (!request.getSessionId().equals(write.sessionId)) return "session_mismatch";
        if (!request.getRequestId().equals(write.requestId)) return "request_id_mismatch";
        if (!request.getActor().name().equals(write.actor)) return "actor_mismatch";
        if (request.getEpoch() != write.requestEpoch) return "epoch_mismatch";
        if (!EpisodicFactCodec.eventIdFor(request, write.startedAtMillis)
                .equals(eventIdOf(write.summaryJson))) return "event_id_mismatch";
        return isSafeEpisodicWrite(write) ? null : "invalid_summary";
    }

    @Override
    public boolean writeSemantic(AgentRequest request, String key, String value, double score) {
        return writeSemanticDetailed(request, key, value, score) == MemoryWriteOutcome.SAVED;
    }

    @Override
    public MemoryWriteOutcome writeSemanticDetailed(AgentRequest request, String key, String value,
            double score) {
        if (!hasMemoryIdentity(request)
                || !isAcceptableSourceSessionId(request.getSessionId())) return MemoryWriteOutcome.INVALID_REQUEST;
        if (!isAcceptableSemanticKey(key)) return MemoryWriteOutcome.INVALID_KEY;
        if (!isAcceptableSemanticValue(value) || !isAcceptableScore(score)) return MemoryWriteOutcome.INVALID_VALUE;
        if (transactionRunner == null || memoryRecordDao == null) return MemoryWriteOutcome.STORAGE_FAILURE;
        String userId = ActorUsers.userIdOf(request);
        String zone = request.getOccupantZone().wireValue();
        try {
            MemoryWriteOutcome[] outcome = {MemoryWriteOutcome.STORAGE_FAILURE};
            transactionRunner.runInTransaction(() -> {
                Long current = readEpochFromSystemRow();
                if (current == null) return;
                if (request.getEpoch() != current) {
                    outcome[0] = MemoryWriteOutcome.STALE_EPOCH;
                    return;
                }
                if (memoryRecordDao.queryByKey(userId, zone, MemoryLayer.SEMANTIC.wireValue(), key) == null
                        && memoryRecordDao.countByUserZoneLayer(userId, zone, MemoryLayer.SEMANTIC.wireValue())
                        >= RoomMemoryStore.MAX_EXPLICIT_RECORDS_PER_SCOPE) {
                    outcome[0] = MemoryWriteOutcome.CAPACITY_REACHED;
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
                row.sourceSessionId = request.getSessionId();
                memoryRecordDao.upsert(row);
                outcome[0] = MemoryWriteOutcome.SAVED;
            });
            return outcome[0];
        } catch (RuntimeException failure) {
            Log.w(TAG, "[MemoryWriter] semantic write failed cause=" + failure.getClass().getSimpleName());
            return MemoryWriteOutcome.STORAGE_FAILURE;
        }
    }

    @Override
    public String readSemantic(AgentRequest request, String key) {
        if (!hasMemoryIdentity(request)) return null;
        return readSemanticScoped(ActorUsers.userIdOf(request),
                request.getOccupantZone().wireValue(), key);
    }

    private String readSemanticScoped(String userId, String zone, String key) {
        if (memoryRecordDao == null || userId == null || userId.isBlank()
                || RoomMemoryStore.SYSTEM_USER.equals(userId)
                || zone == null || !isAcceptableSemanticKey(key)) return null;
        VehicleZone canonicalZone = VehicleZone.parse(zone);
        if (canonicalZone == null) return null;
        try {
            MemoryRecordEntity row = memoryRecordDao.queryByKey(userId, canonicalZone.wireValue(),
                    MemoryLayer.SEMANTIC.wireValue(), key);
            return row == null ? null : row.value;
        } catch (Exception ex) {
            Log.w(TAG, "[MemoryWriter] semantic read FAILED keyLen=" + key.length()
                    + " cause=" + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return null;
        }
    }

    @Override
    public MemoryDeleteOutcome deleteSemanticDetailed(AgentRequest request, String key) {
        if (!hasMemoryIdentity(request)) return MemoryDeleteOutcome.INVALID_REQUEST;
        return deleteSemanticScoped(ActorUsers.userIdOf(request),
                request.getOccupantZone().wireValue(), key, request.getEpoch());
    }

    private MemoryDeleteOutcome deleteSemanticScoped(String userId, String zone, String key,
            long requestEpoch) {
        if (!isAcceptableSemanticKey(key)) return MemoryDeleteOutcome.INVALID_KEY;
        VehicleZone canonicalZone = VehicleZone.parse(zone);
        if (transactionRunner == null || memoryRecordDao == null || userId == null
                || userId.isBlank() || RoomMemoryStore.SYSTEM_USER.equals(userId)
                || canonicalZone == null || !isAcceptableSemanticKey(key)) {
            return MemoryDeleteOutcome.STORAGE_FAILURE;
        }
        try {
            MemoryDeleteOutcome[] outcome = {MemoryDeleteOutcome.STORAGE_FAILURE};
            transactionRunner.runInTransaction(() -> {
                Long current = readEpochFromSystemRow();
                if (current == null) return;
                if (current != requestEpoch) {
                    outcome[0] = MemoryDeleteOutcome.STALE_EPOCH;
                    return;
                }
                outcome[0] = memoryRecordDao.deleteByKey(userId, canonicalZone.wireValue(),
                        MemoryLayer.SEMANTIC.wireValue(), key) > 0
                        ? MemoryDeleteOutcome.DELETED : MemoryDeleteOutcome.NOT_FOUND;
            });
            return outcome[0];
        } catch (Exception failure) {
            Log.w(TAG, "[MemoryWriter] semantic delete failed cause="
                    + failure.getClass().getSimpleName());
            return MemoryDeleteOutcome.STORAGE_FAILURE;
        }
    }

    @Override
    public String readEpisodic(AgentRequest request, String eventId) {
        SessionHistoryEntity row = findEpisodic(request, eventId);
        if (row == null) return null;
        try {
            JSONObject stored = new JSONObject(row.trajectoryJson);
            JSONObject projected = new JSONObject();
            projected.put("eventKind", stored.getString("eventKind"));
            projected.put("finalState", row.finalState);
            projected.put("startedAtMillis", row.startedAtMillis);
            projected.put("verifiedFacts", EpisodicFactCodec.project(
                    stored.getJSONArray("verifiedFacts")));
            return projected.toString();
        } catch (Exception invalid) { return null; }
    }

    @Override
    public MemoryDeleteOutcome deleteEpisodic(AgentRequest request, String eventId) {
        if (!hasMemoryIdentity(request)) return MemoryDeleteOutcome.INVALID_REQUEST;
        if (!EpisodicFactCodec.validEventId(eventId)) return MemoryDeleteOutcome.INVALID_KEY;
        if (!MemoryKeyCatalog.episodicDeleteAuthorized(eventId, request.getText())) {
            return MemoryDeleteOutcome.TARGET_NOT_AUTHORIZED;
        }
        if (transactionRunner == null || sessionHistoryDao == null) return MemoryDeleteOutcome.STORAGE_FAILURE;
        try {
            MemoryDeleteOutcome[] outcome = {MemoryDeleteOutcome.STORAGE_FAILURE};
            transactionRunner.runInTransaction(() -> {
                Long current = readEpochFromSystemRow();
                if (current == null) return;
                if (current != request.getEpoch()) {
                    outcome[0] = MemoryDeleteOutcome.STALE_EPOCH;
                    return;
                }
                SessionHistoryEntity row = findEpisodic(request, eventId);
                if (row == null) {
                    outcome[0] = MemoryDeleteOutcome.NOT_FOUND;
                    return;
                }
                outcome[0] = sessionHistoryDao.deleteExact(row.userId, row.zone,
                        row.sessionId, row.startedAtMillis) > 0
                        ? MemoryDeleteOutcome.DELETED : MemoryDeleteOutcome.NOT_FOUND;
            });
            return outcome[0];
        } catch (Exception failure) {
            Log.w(TAG, "[MemoryWriter] episodic delete failed cause="
                    + failure.getClass().getSimpleName());
            return MemoryDeleteOutcome.STORAGE_FAILURE;
        }
    }

    private SessionHistoryEntity findEpisodic(AgentRequest request, String eventId) {
        if (!hasMemoryIdentity(request) || sessionHistoryDao == null
                || !EpisodicFactCodec.validEventId(eventId)) return null;
        try {
            String user = ActorUsers.userIdOf(request);
            String zone = request.getOccupantZone().wireValue();
            long cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1_000;
            for (SessionHistoryEntity row : sessionHistoryDao.queryByUserZone(user, zone, 100)) {
                try {
                    if (row.startedAtMillis < cutoff || row.trajectoryJson == null
                            || row.trajectoryJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 2048) {
                        continue;
                    }
                    JSONObject summary = new JSONObject(row.trajectoryJson);
                    if (summary.optInt("eventSchemaVersion", -1) != EpisodicEventKind.SCHEMA_VERSION
                            || !eventId.equals(summary.optString("eventId"))) continue;
                    if (!EpisodicFactCodec.validFactsForCapabilities(
                            summary.optJSONArray("verifiedFacts"),
                            summary.optJSONArray("successfulCapabilities"))
                            || EpisodicEventKind.fromWireValue(summary.optString("eventKind")) == null
                            || summary.optLong("startedAtMillis", -1) != row.startedAtMillis
                            || !("SUCCEEDED".equals(row.finalState) || "FAILED".equals(row.finalState))
                            || !row.finalState.equals(summary.optString("finalState"))) continue;
                    return row;
                } catch (Exception invalidRow) {
                    // One corrupt legacy row must not hide later events in the scoped window.
                }
            }
        } catch (Exception failure) {
            Log.w(TAG, "[MemoryWriter] episodic lookup failed cause="
                    + failure.getClass().getSimpleName());
        }
        return null;
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
            if (row == null) return 0L;  // 合法初始
            if (row.value == null) return null;
            long loaded = Long.parseLong(row.value);
            return loaded < 0 ? null : loaded;
        } catch (Exception ex) {
            Log.w(TAG, "[MemoryWriter] readEpochFromSystemRow FAILED, fail-closed reject cause="
                    + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return null;  // 不再 0L 兜底——把"读取失败"伪装成"epoch=0"会让 stale 写入通过
        }
    }

    /** Absence of an occupant scope never grants GLOBAL access, including malformed test objects. */
    private static boolean hasMemoryIdentity(AgentRequest request) {
        return request != null && request.getActor() != null && request.getOccupantZone() != null;
    }

    // writer-side validation helpers。与 CapabilityRegistry.memory.semantic.save
    // schema 同款约束。Schema 已强制,但 writer 仍兜底防 Provider 漏检 / 第三方 Provider / 测试桩。

    private static boolean isAcceptableSemanticKey(String key) {
        return MemoryKeyCatalog.isSemanticKey(key);
    }

    private static boolean isAcceptableSemanticValue(String value) {
        return value != null && !value.isBlank() && value.length() <= SEMANTIC_VALUE_MAX_LEN;
    }

    private static boolean isAcceptableScore(double score) {
        return Double.isFinite(score) && score >= 0.0 && score <= 1.0;
    }

    private static boolean isAcceptableSourceSessionId(String sessionId) {
        if (sessionId == null) return true;  // 历史数据允许 null
        return sessionId.length() <= SOURCE_SESSION_ID_MAX_LEN;
    }

    private static boolean isSafeEpisodicWrite(EpisodicWrite write) {
        if (write.userId == null || write.userId.isBlank()
                || RoomMemoryStore.SYSTEM_USER.equals(write.userId)
                || write.sessionId == null || write.sessionId.isBlank()
                || write.sessionId.length() > SOURCE_SESSION_ID_MAX_LEN
                || !("DRIVER".equals(write.actor) || "PASSENGER".equals(write.actor))
                || (write.stopReason != null && !write.stopReason.isEmpty()
                        && !isKnownStopReason(write.stopReason))
                || write.summaryJson == null
                || write.summaryJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 2048
                || write.durationMs < 0 || write.turnCount < 0
                || !("SUCCEEDED".equals(write.finalState) || "FAILED".equals(write.finalState))) {
            return false;
        }
        try {
            JSONObject json = new JSONObject(write.summaryJson);
            java.util.Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!java.util.Set.of("eventSchemaVersion", "eventKind", "eventId", "verifiedFacts", "startedAtMillis",
                        "finalState", "durationMs", "turnCount", "successfulCapabilities",
                        "truncated").contains(key)) {
                    return false;
                }
            }
            int version = json.optInt("eventSchemaVersion", -1);
            if (!write.finalState.equals(json.optString("finalState"))
                    || version != EpisodicEventKind.SCHEMA_VERSION
                    || !json.has("eventKind")
                    || json.optLong("startedAtMillis", -1) != write.startedAtMillis
                    || json.optLong("durationMs", -1) != write.durationMs
                    || json.optInt("turnCount", -1) != write.turnCount) return false;
            if (version == EpisodicEventKind.SCHEMA_VERSION
                    && !EpisodicFactCodec.validEventId(json.optString("eventId", null))) return false;
            JSONArray capabilities = json.optJSONArray("successfulCapabilities");
            if (version == EpisodicEventKind.SCHEMA_VERSION
                    && !EpisodicFactCodec.validFactsForCapabilities(
                            json.optJSONArray("verifiedFacts"), capabilities)) return false;
            if (capabilities != null) {
                if (capabilities.length() > 3) return false;
                EpisodicEventKind derivedKind = null;
                for (int i = 0; i < capabilities.length(); i++) {
                    String capability = capabilities.optString(i, "");
                    if (capability.length() > 64
                            || !Pattern.matches("[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+", capability)
                            || EpisodicEventKind.fromCapability(capability) == null) return false;
                    if (derivedKind == null) derivedKind = EpisodicEventKind.fromCapability(capability);
                }
                if (derivedKind == null ? !json.isNull("eventKind")
                        : !derivedKind.wireValue().equals(json.optString("eventKind"))) return false;
            } else if (!json.optBoolean("truncated", false)) {
                return false;
            }
            return true;
        } catch (Exception invalid) {
            return false;
        }
    }

    private static boolean isKnownStopReason(String value) {
        return value.length() <= 32 && Pattern.matches("[A-Z_]+", value);
    }

    private static String eventIdOf(String summaryJson) {
        if (summaryJson == null || summaryJson.length() > 2048) return null;
        try {
            return new JSONObject(summaryJson).optString("eventId", null);
        } catch (Exception invalid) {
            return null;
        }
    }
}
