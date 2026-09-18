package com.matrix.agent.data.audit;

import android.util.Log;

import com.matrix.agent.data.db.AuditEventDao;
import com.matrix.agent.data.db.AuditEventEntity;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * audit_event 增量事件落库——PRE_TOOL / POST_TOOL / POLICY / STEER。
 *
 * <p>AgentEngine 5 个出口点调用本类(fire-and-forget 异步 insert),与旧版 auditRepository.persist
 * (走 TrajectoryEntity 同步落库)协同——audit_event 表记录 per-event 增量,trajectory 表记录
 * 完整 iteration。
 *
 * <p><b>fail-open</b>:Dao 异常 / Executor 拒绝 / 队列溢出 → 仅 log,不阻塞主路径(与
 * RoomAuditRepository.persist 一致语义);Terminal 事件仍由 auditRepository.persist 同步写入
 * TrajectoryEntity,保证终态可回放。
 *
 * <p><b>NOOP 单例</b>:Dao 为 null 时退化为 {@link #NOOP}(AuditRuntimeGraph
 * 失败时 fallback);所有 recordXxx 方法 null-safe。
 *
 * <p><b>batch 升级</b>:生产构造器 {@link #AuditEventRecorder(AuditEventDao)}
 * 启动 daemon {@link ScheduledExecutorService},每 {@value #BATCH_FLUSH_INTERVAL_MS}ms 调
 * {@link #drainAndFlush()} 把 {@link #pending} 队列批量落库;{@link #flushSync()} 直接同步
 * drain + insert(线程安全,与 scheduler 并发安全——{@link ConcurrentLinkedQueue#poll()} 单消费语义)。
 * 队列上限 {@value #QUEUE_CAP},溢出时丢最老 PRE_TOOL(POST_TOOL/POLICY/STEER 优先保留——
 * 后两者含审计关键信号)。测试构造器 {@link #AuditEventRecorder(AuditEventDao, ExecutorService)}
 * 仍走"每条独立 submit"语义,单测零回归。
 */
public final class AuditEventRecorder {
    private static final String TAG = "MatrixAgent";

    /** batch 单次 flush 上限——避免 scheduler tick 拖过长。 */
    static final int BATCH_SIZE = 50;
    /** batch 触发周期——200ms 一次 drainAndFlush,保证延迟可观测。 */
    static final long BATCH_FLUSH_INTERVAL_MS = 200L;
    /** 队列上限——超过则丢最老 PRE_TOOL,POST_TOOL/POLICY/STEER 优先保留。 */
    static final int QUEUE_CAP = 500;

    /**
     * NOOP 单例——AuditRuntimeGraph 初始化失败时 fallback。
     * 所有 recordXxx 直接 return,不抛、不写、不日志。
     */
    public static final AuditEventRecorder NOOP =
            new AuditEventRecorder(null, (ExecutorService) null);

    private final AuditEventDao dao;
    private final ExecutorService executor;  // 测试路径用(non-null 时 batch 关闭)
    private final boolean batchEnabled;
    private final ConcurrentLinkedQueue<AuditEventEntity> pending;
    private final ScheduledExecutorService scheduler;
    private final boolean ownsScheduler;
    /** Serializes periodic and caller-requested flushes; Room writes must not overlap. */
    private final Object flushLock = new Object();
    /** Guards scheduler execution across error and shutdown races. */
    private final AtomicBoolean scheduledFlushInFlight = new AtomicBoolean();
    private volatile boolean running;
    private final AtomicLong queued = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong queueOverflowDropped = new AtomicLong();
    /**
     * 全局 stale epoch gate。Repository.clearUserDataDetailed 自增 epoch 后
     * 调 advanceEpoch(newEpoch),所有 happenedAtMs < newEpoch 的事件被 drop。
     *
     * <p>默认 0 表示未启用——只有 Repository 显式 advance 过才生效。
     */
    private volatile long staleEpoch = 0L;
    /**
     * per-(userId+zone) stale epoch gate。
     *
     * <p>dropByUserZone(userId, zone, staleEpochHint) 加 entry;后续 record() 时如果
     * entity.userId+zone 命中且 happenedAtMs < hint → drop。比全局 staleEpoch 更精确,
     * 不影响其他 user/zone 后续 record。
     */
    private final ConcurrentHashMap<String, Long> staleUserZoneEpochs = new ConcurrentHashMap<>();

    /** 生产构造器——启用 batch 队列 + daemon ScheduledExecutorService。 */
    public AuditEventRecorder(AuditEventDao dao) {
        this.dao = dao;
        this.executor = null;
        this.batchEnabled = dao != null;
        this.pending = batchEnabled ? new ConcurrentLinkedQueue<AuditEventEntity>() : null;
        this.running = batchEnabled;
        if (batchEnabled) {
            this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "audit-event-batch");
                t.setDaemon(true);
                return t;
            });
            // Fixed delay deliberately avoids catch-up flushes after slow SQLCipher I/O.
            this.scheduler.scheduleWithFixedDelay(this::scheduledFlush,
                    BATCH_FLUSH_INTERVAL_MS, BATCH_FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
            this.ownsScheduler = true;
        } else {
            this.scheduler = null;
            this.ownsScheduler = false;
        }
    }

    /** Production Host path: batch scheduling uses the process-wide bounded registry. */
    public AuditEventRecorder(AuditEventDao dao, ScheduledExecutorService scheduler) {
        if (dao == null || scheduler == null) throw new IllegalArgumentException("dao/scheduler required");
        this.dao = dao;
        this.executor = null;
        this.batchEnabled = true;
        this.pending = new ConcurrentLinkedQueue<>();
        this.running = true;
        this.scheduler = scheduler;
        this.ownsScheduler = false;
        this.scheduler.scheduleWithFixedDelay(this::scheduledFlush,
                BATCH_FLUSH_INTERVAL_MS, BATCH_FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * 测试用——注入自定义 Executor(同步 Executor 验证 fail-open / 顺序);batch 关闭,
     * 每条 recordXxx 直接提交一条 insert Runnable,单测零回归。
     */
    public AuditEventRecorder(AuditEventDao dao, ExecutorService executor) {
        this.dao = dao;
        this.executor = executor;
        this.batchEnabled = false;
        this.pending = null;
        this.scheduler = null;
        this.ownsScheduler = false;
        this.running = false;
    }

    /** scheduler 周期触发——drain 整个 pending 一次性 flush(单次上限 BATCH_SIZE*4)。 */
    private void scheduledFlush() {
        if (!running || !scheduledFlushInFlight.compareAndSet(false, true)) return;
        try {
            synchronized (flushLock) {
                if (running) {
                    drainAndFlush();
                }
            }
        } catch (Throwable ex) {
            // scheduler 不能因单次 flush 异常退出——会导致后续事件永久积压
            Log.e(TAG, "[AuditEventRecorder] scheduledFlush exception cause="
                    + ex.getClass().getSimpleName() + ": " + ex.getMessage(), ex);
        } finally {
            scheduledFlushInFlight.set(false);
        }
    }

    /**
     * drain pending 队列并 batch insert。
     *
     * <p>仅由 {@link #flushLock} 保护的入口调用，保证 scheduler 与 {@link #flushSync()}
     * 不会并发写 Room；队列仍使用 poll 保持单消费语义。
     */
    private void drainAndFlush() {
        if (pending == null || pending.isEmpty()) return;
        List<AuditEventEntity> buffer = new ArrayList<>(BATCH_SIZE);
        AuditEventEntity e;
        int safetyCap = BATCH_SIZE * 4;  // 单次 flush 最多 200 条,防止单 tick 过长
        while (buffer.size() < safetyCap && (e = pending.poll()) != null) {
            buffer.add(e);
        }
        if (!buffer.isEmpty()) {
            flushBuffer(buffer);
        }
    }

    private void flushBuffer(List<AuditEventEntity> buffer) {
        if (buffer.isEmpty() || dao == null) return;
        for (AuditEventEntity entity : buffer) {
            // 事件入队后 stale gate 可能已 advance——insert 前再查一次,
            // 杜绝 scheduler tick 在 clearUserDataDetailed step3→step7 之间把旧 epoch 事件落库。
            if (isStale(entity)) {
                dropped.incrementAndGet();
                Log.w(TAG, "[AuditEventRecorder] stale event dropped at flush type=" + entity.type
                        + " requestId=" + entity.requestId
                        + " userId=" + entity.userId + " zone=" + entity.zone
                        + " happenedAtMs=" + entity.happenedAtMs);
                continue;
            }
            try {
                dao.insert(entity);
                queued.incrementAndGet();
            } catch (Exception ex) {
                dropped.incrementAndGet();
                Log.w(TAG, "[AuditEventRecorder] insert FAILED type=" + entity.type
                        + " requestId=" + entity.requestId
                        + " cause=" + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            }
        }
    }

    public void recordPreTool(String requestId, String userId, String zone, String actor,
            String toolName, Map<String, Object> redactedArgs, String argsJsonPreview) {
        recordPreTool(requestId, userId, zone, actor, toolName, redactedArgs, argsJsonPreview, 0L);
    }

    /**
     * 带 requestEpoch 的重载——AgentEngine 5 处调用方透传
     * {@code request.getEpoch()},让 stale gate 用 epoch 而非毫秒时间戳比较。
     */
    public void recordPreTool(String requestId, String userId, String zone, String actor,
            String toolName, Map<String, Object> redactedArgs, String argsJsonPreview,
            long requestEpoch) {
        record(AuditEventTypes.PRE_TOOL, requestId, userId, zone, actor,
                buildPreToolPayload(toolName, redactedArgs, argsJsonPreview), requestEpoch);
    }

    public void recordPostTool(String requestId, String userId, String zone, String actor,
            String toolName, String status, boolean verified, long durationMs,
            String resultPreview) {
        recordPostTool(requestId, userId, zone, actor, toolName, status, verified, durationMs,
                resultPreview, 0L);
    }

    /** 带 requestEpoch 的 POST_TOOL 重载。 */
    public void recordPostTool(String requestId, String userId, String zone, String actor,
            String toolName, String status, boolean verified, long durationMs,
            String resultPreview, long requestEpoch) {
        record(AuditEventTypes.POST_TOOL, requestId, userId, zone, actor,
                buildPostToolPayload(toolName, status, verified, durationMs, resultPreview),
                requestEpoch);
    }

    public void recordPolicyDecision(String requestId, String userId, String zone, String actor,
            String toolName, String rejectionType, String reasonPreview) {
        recordPolicyDecision(requestId, userId, zone, actor, toolName, rejectionType, reasonPreview,
                0L);
    }

    /** 带 requestEpoch 的 POLICY 重载。 */
    public void recordPolicyDecision(String requestId, String userId, String zone, String actor,
            String toolName, String rejectionType, String reasonPreview, long requestEpoch) {
        record(AuditEventTypes.POLICY, requestId, userId, zone, actor,
                buildPolicyPayload(toolName, rejectionType, reasonPreview), requestEpoch);
    }

    public void recordSteer(String requestId, String userId, String zone, String actor,
            String steerType, int payloadChars) {
        recordSteer(requestId, userId, zone, actor, steerType, payloadChars, 0L);
    }

    /** 带 requestEpoch 的 STEER 重载。 */
    public void recordSteer(String requestId, String userId, String zone, String actor,
            String steerType, int payloadChars, long requestEpoch) {
        record(AuditEventTypes.STEER, requestId, userId, zone, actor,
                buildSteerPayload(steerType, payloadChars), requestEpoch);
    }

    public void recordSteerDroppedStale(String requestId, String userId, String zone, String actor,
            String steerType, int payloadChars) {
        recordSteerDroppedStale(requestId, userId, zone, actor, steerType, payloadChars, 0L);
    }

    /** 带 requestEpoch 的 STEER_DROPPED_STALE 重载。 */
    public void recordSteerDroppedStale(String requestId, String userId, String zone, String actor,
            String steerType, int payloadChars, long requestEpoch) {
        record(AuditEventTypes.STEER_DROPPED_STALE, requestId, userId, zone, actor,
                buildSteerPayload(steerType, payloadChars), requestEpoch);
    }

    private void record(String type, String requestId, String userId, String zone, String actor,
            String payloadJson, long requestEpoch) {
        if (dao == null) return;  // NOOP
        AuditEventEntity entity = new AuditEventEntity();
        entity.requestId = requestId;
        entity.userId = userId == null ? "" : userId;
        entity.zone = zone == null ? "" : zone;
        entity.actor = actor == null ? "" : actor;
        entity.type = type;
        entity.happenedAtMs = System.currentTimeMillis();
        entity.payloadJson = payloadJson;
        entity.requestEpoch = requestEpoch;

        // stale gate 入队前检查——用 requestEpoch(MemoryStore 版本号)比较,
        // 不再用 happenedAtMs(毫秒时间戳)。requestEpoch=0 表示未透传(老数据 / 测试),不参与 gate。
        if (isStale(entity)) {
            dropped.incrementAndGet();
            Log.w(TAG, "[AuditEventRecorder] stale event dropped at enqueue type=" + entity.type
                    + " requestId=" + entity.requestId
                    + " userId=" + entity.userId + " zone=" + entity.zone
                    + " requestEpoch=" + entity.requestEpoch + " staleEpoch=" + staleEpoch);
            return;
        }

        if (batchEnabled) {
            enqueueOrDrop(entity);
        } else if (executor != null) {
            submitImmediate(entity);
        }
    }

    /**
     * stale gate——用 requestEpoch(MemoryStore 版本号)比较,不再用 happenedAtMs。
     *
     * <p>修复的次生 bug:happenedAtMs 是毫秒时间戳(17xxx...),staleEpoch 是版本号(1/2/3),
     * 永远 false,旧 epoch 事件不被 drop,clearUserData 后异步事件可能"复活"。
     *
     * <p>新规则:
     * <ul>
     *   <li>{@code entity.requestEpoch == 0}:未透传(老数据 / 测试),不参与 gate,保留旧版行为;</li>
     *   <li>全局 staleEpoch > 0 且 {@code entity.requestEpoch < staleEpoch}(整个进程级别的旧 epoch)→ drop;</li>
     *   <li>(userId+zone) 在 staleUserZoneEpochs 中且 {@code entity.requestEpoch < hint}(per-zone 精确清理)→ drop。</li>
     * </ul>
     */
    private boolean isStale(AuditEventEntity entity) {
        if (entity.requestEpoch <= 0L) return false;  // 未透传 / 老数据
        long global = staleEpoch;
        if (global > 0L && entity.requestEpoch < global) {
            return true;
        }
        Long perZone = staleUserZoneEpochs.get(entity.userId + "@" + entity.zone);
        return perZone != null && entity.requestEpoch < perZone;
    }

    /**
     * 推进全局 stale epoch——Repository.clearUserDataDetailed 在 epoch 自增后调。
     *
     * <p>后续 record() 时 happenedAtMs < newEpoch 的实体直接 drop(不入队)。已入队的实体由
     * {@link #dropByUserZone(String, String, long)} 显式 drain,或被 scheduler flush 时
     * 再次检查 isStale 拒绝 insert(双保险)。
     */
    public void advanceEpoch(long newEpoch) {
        if (newEpoch <= 0L) return;
        if (newEpoch > staleEpoch) {
            staleEpoch = newEpoch;
            Log.i(TAG, "[AuditEventRecorder] advanceEpoch -> " + newEpoch
                    + " (subsequent events with happenedAtMs < epoch will be dropped)");
        }
    }

    /**
     * drain pending 队列中匹配 (userId, zone) 的事件,并加 stale gate。
     *
     * <p>由 Repository.clearUserDataDetailed 在 clearByUserZone 之前调用——把 pending 队列里
     * 指定 user+zone 的事件移除,避免 scheduler tick 在 clearByUserZone 之后把它们重新 insert。
     * 同时设置 per-(userId+zone) stale epoch hint,后续 record() 同 user+zone 的旧 epoch 事件
     * 也被 drop。
     *
     * <p>线程安全——pending 是 ConcurrentLinkedQueue,Iterator 弱一致;staleUserZoneEpochs 是
     * ConcurrentHashMap。Scheduler 与 flushSync 并发安全。
     */
    public void dropByUserZone(String userId, String zone, long staleEpochHint) {
        if (userId == null || zone == null) return;
        String gateKey = userId + "@" + zone;
        if (staleEpochHint > 0L) {
            staleUserZoneEpochs.merge(gateKey, staleEpochHint, Math::max);
        }
        if (pending == null) return;
        int removed = 0;
        Iterator<AuditEventEntity> it = pending.iterator();
        while (it.hasNext()) {
            AuditEventEntity e = it.next();
            if (userId.equals(e.userId) && zone.equals(e.zone)) {
                it.remove();
                removed++;
                dropped.incrementAndGet();
            }
        }
        if (removed > 0) {
            Log.i(TAG, "[AuditEventRecorder] dropByUserZone user=" + userId + " zone=" + zone
                    + " removed=" + removed + " pendingSize=" + pending.size());
        }
    }

    /** 批量 enqueue——队列超 QUEUE_CAP 时丢最老 PRE_TOOL(优先保 POST/POLICY/STEER)。 */
    private void enqueueOrDrop(AuditEventEntity entity) {
        if (pending.size() >= QUEUE_CAP) {
            boolean evicted = evictOldestPreTool();
            if (!evicted) {
                // 队列全是 POST/POLICY/STEER——只能丢当前事件
                queueOverflowDropped.incrementAndGet();
                dropped.incrementAndGet();
                Log.w(TAG, "[AuditEventRecorder] queue overflow, drop type=" + entity.type
                        + " requestId=" + entity.requestId + " pendingSize=" + pending.size());
                return;
            }
        }
        pending.offer(entity);
    }

    private boolean evictOldestPreTool() {
        for (AuditEventEntity e : pending) {
            if (AuditEventTypes.PRE_TOOL.equals(e.type)) {
                if (pending.remove(e)) {
                    queueOverflowDropped.incrementAndGet();
                    dropped.incrementAndGet();
                    Log.w(TAG, "[AuditEventRecorder] queue overflow, evict oldest PRE_TOOL requestId="
                            + e.requestId);
                    return true;
                }
            }
        }
        return false;
    }

    private void submitImmediate(AuditEventEntity entity) {
        try {
            executor.execute(() -> {
                // executor 异步执行时 stale gate 可能已 advance——再查一次。
                if (isStale(entity)) {
                    dropped.incrementAndGet();
                    Log.w(TAG, "[AuditEventRecorder] stale event dropped at submit type=" + entity.type
                            + " requestId=" + entity.requestId);
                    return;
                }
                try {
                    dao.insert(entity);
                    queued.incrementAndGet();
                } catch (Exception ex) {
                    dropped.incrementAndGet();
                    Log.w(TAG, "[AuditEventRecorder] insert FAILED type=" + entity.type
                            + " requestId=" + entity.requestId
                            + " cause=" + ex.getClass().getSimpleName() + ": " + ex.getMessage());
                }
            });
        } catch (RejectedExecutionException rejected) {
            dropped.incrementAndGet();
            Log.w(TAG, "[AuditEventRecorder] executor rejected type=" + entity.type
                    + " requestId=" + entity.requestId + " (queue full / shutdown)");
        }
    }

    /**
     * 同步 flush——drain pending 队列并同步 insert。
     *
     * <p>用于 Terminal 事件 / 任务返回前 / 测试断言前——保证"任务返回后立即查询 audit"无竞态。
     * 与 scheduler 的 scheduledFlush 由同一 flushLock 串行，返回时此前 pending 已完成本轮落库。
     */
    public void flushSync() {
        if (!batchEnabled || pending == null) return;
        synchronized (flushLock) {
            drainAndFlush();
        }
    }

    /**
     * 测试辅助——同步等待指定条数已落库(queued 计数 ≥ expected)。
     *
     * <p>比 flushSync 更精确:即使 scheduler 未触发,可用此方法等待具体 N 条出现。超时 timeoutMillis。
     */
    public void awaitQueuedCount(long expected, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (queued.get() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(5L);
        }
    }

    /**
     * 全部 payload 用 {@link JSONObject} 拼装,合法 escape 特殊字符。
     *
     * <p>旧实现用 StringBuilder 手工拼接 + 仅 replace 双引号,反斜杠 / 换行 / 控制字符会破坏 JSON 结构。
     * 改用 JSONObject.put + toString,与 TrajectoryCodec / ModelApiClient 一致,JSONObject 自动 escape。
     * redactedArgs 已是 {@code Map<String,Object>},直接 JSONObject 包装产生合法嵌套 JSON。
     */
    static String buildPreToolPayload(String toolName, Map<String, Object> redactedArgs,
            String argsJsonPreview) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("tool", toolName);
            if (redactedArgs != null && !redactedArgs.isEmpty()) {
                obj.put("args", new JSONObject(redactedArgs));
            } else if (argsJsonPreview != null) {
                obj.put("args", argsJsonPreview);
            }
        } catch (org.json.JSONException ex) {
            // fallback 也用 JSONObject 构造,避免 JSONObject.quote() 返回带引号
            // 字符串后拼到已有 "..." 内导致双重引号(非法 JSON)。
            Log.w(TAG, "[AuditEventRecorder] buildPreToolPayload JSONException, fallback cause="
                    + ex.getMessage());
            JSONObject fallback = new JSONObject();
            try {
                fallback.put("tool", toolName == null ? "" : toolName);
                fallback.put("args", "");
            } catch (org.json.JSONException ignored) {
                // 不应发生——空 JSONObject + 字符串 value 不会抛
            }
            return fallback.toString();
        }
        return obj.toString();
    }

    private static String buildPostToolPayload(String toolName, String status, boolean verified,
            long durationMs, String resultPreview) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("tool", toolName);
            obj.put("status", status);
            obj.put("verified", verified);
            obj.put("durationMs", durationMs);
            obj.put("result", resultPreview == null ? "" : resultPreview);
        } catch (org.json.JSONException ex) {
            Log.w(TAG, "[AuditEventRecorder] buildPostToolPayload JSONException cause="
                    + ex.getMessage());
        }
        return obj.toString();
    }

    private static String buildPolicyPayload(String toolName, String rejectionType,
            String reasonPreview) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("tool", toolName);
            obj.put("rejectionType", rejectionType);
            obj.put("reason", reasonPreview == null ? "" : reasonPreview);
        } catch (org.json.JSONException ex) {
            Log.w(TAG, "[AuditEventRecorder] buildPolicyPayload JSONException cause="
                    + ex.getMessage());
        }
        return obj.toString();
    }

    private static String buildSteerPayload(String steerType, int payloadChars) {
        JSONObject obj = new JSONObject();
        try {
            obj.put("steerType", steerType);
            obj.put("payloadChars", payloadChars);
        } catch (org.json.JSONException ex) {
            Log.w(TAG, "[AuditEventRecorder] buildSteerPayload JSONException cause="
                    + ex.getMessage());
        }
        return obj.toString();
    }

    /** 测试用——已入队事件数(异步 insert 完成后递增)。 */
    public long getQueuedCount() {
        return queued.get();
    }

    /** 测试用——因 Dao 异常 / Executor 拒绝 / 队列溢出丢弃的事件数。 */
    public long getDroppedCount() {
        return dropped.get();
    }

    /** 测试用——队列溢出丢弃数(含 evict + 当前事件丢)。 */
    public long getQueueOverflowDropped() {
        return queueOverflowDropped.get();
    }

    /** 测试用——当前 pending 队列长度。 */
    public int getPendingSize() {
        return pending == null ? 0 : pending.size();
    }

    /** 测试 / shutdown 用——关闭 scheduler / Executor。生产 AppContainer 不调用(单例生命周期)。 */
    public void shutdown() {
        running = false;
        if (scheduler != null) {
            if (ownsScheduler) {
                scheduler.shutdown();
                try {
                    scheduler.awaitTermination(1L, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            // final drain:确保 shutdown 前所有 pending 都落库
            synchronized (flushLock) {
                drainAndFlush();
            }
        }
        if (executor != null) executor.shutdown();
    }
}
