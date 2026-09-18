package com.matrix.agent.task.steer;
import com.matrix.agent.task.*;

import com.matrix.agent.identity.AgentRequest;

import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-session Steer 队列。
 *
 * <p>外部线程(主驾 / 副驾 UI)在任务执行中调用 {@link #offer} 追加转向指令;
 * Agent Loop 在两个 drain 点(L178 前 LLM 调用前 / L264 前 Tool 执行前)取出并消费。
 *
 * <p>线程安全:每个 session 独立队列,使用 {@link ConcurrentLinkedQueue} 无锁实现。
 * 不同 session 互不干扰,同一 session 内严格 FIFO。
 *
 * <p>不阻塞 Loop:drain 操作 O(n) 一次性取出全部,空时不消耗任何资源。
 * 不持久化:JVM 重启即丢失(接入 Room 持久化时再补)。
 *
 * <p><b>epoch gate</b>——clearUserData 推进 memoryStore epoch 时,
 * Repository 同步调 {@link #advanceEpoch(long)} 把 SteerMailbox 的 currentEpoch 推到新值。
 * 后续 {@link #drain(String, long)} 看到 stamped.epoch &lt; currentEpoch 的 Steer → drop +
 * audit(STEER_DROPPED_STALE),不进 conversation。杜绝"clearUserData 前用户已 offer 的 FORCE_TOOL
 * 在 clearUserData 后被新任务消费到"——这是旧版 SLO 漏洞。
 *
 * <p><b>兼容契约</b>:旧 {@link #drain(String)}(不传 epoch)仍全量返回——历史测试零回归;
 * AgentEngine 主路径切到 {@link #drain(String, long)} 用 request.getEpoch() 触发 epoch gate。
 */
public final class SteerMailbox {
    private static final String TAG = "MatrixAgent";

    /**
     * 内部包装——记录 Steer 入队时的 currentEpoch。
     *
     * <p>StampedSteer 不暴露给 caller;offer / drain 外部 API 仍用 {@link Steer}。
     */
    private static final class StampedSteer {
        final Steer inner;
        final long epoch;

        StampedSteer(Steer inner, long epoch) {
            this.inner = inner;
            this.epoch = epoch;
        }
    }

    private final ConcurrentMap<String, Queue<StampedSteer>> queues = new ConcurrentHashMap<>();

    /**
     * SteerMailbox 的 currentEpoch——由 Repository.clearUserData 推进。
     *
     * <p>初始 0L;offer(sessionId, steer) 默认用本值标记 stamped epoch。
     * advanceEpoch 推到 N 后,drain(sessionId, N) 把 stamped.epoch < N 的 Steer drop + audit。
     */
    private final AtomicLong currentEpoch = new AtomicLong(0L);

    /**
     * stale Steer 处理器——drop 时回调 audit(STEER_DROPPED_STALE)。
     *
     * <p>null 时仅 log warn(不 audit);AppContainer 注入真实 handler 后生效。
     */
    private volatile StaleSteerHandler staleHandler;

    public SteerMailbox() {}

    /**
     * 注入 stale handler——封装 drop + audit 逻辑。
     *
     * <p>避免 SteerMailbox 直接依赖 AuditEventRecorder(单测可注入 fake)。
     */
    public void setStaleHandler(StaleSteerHandler handler) {
        this.staleHandler = handler;
        Log.i(TAG, "[Steer] staleHandler set " + (handler == null ? "null" : "active"));
    }

    /**
     * 推进 currentEpoch——clearUserData 调用。
     *
     * <p>本方法**不自增**——只接收 Repository 推送的"当前 epoch 已变"信号(单一权威 = MemoryStore)。
     * 推进后,所有 stamped.epoch &lt; newEpoch 的 Steer 在下次 drain 时被 drop + audit。
     */
    public void advanceEpoch(long newEpoch) {
        long previous = currentEpoch.getAndSet(newEpoch);
        Log.i(TAG, "[Steer] advanceEpoch -> " + newEpoch + " (was " + previous + ")");
    }

    /** 当前 epoch getter——测试 / 监控用。 */
    public long getCurrentEpoch() {
        return currentEpoch.get();
    }

    /** 把 Steer 投到指定 session 的队列尾(默认 currentEpoch 标记)。其他 session 不受影响。 */
    public void offer(String sessionId, Steer steer) {
        offer(sessionId, steer, currentEpoch.get());
    }

    /**
     * 带 epoch 的 offer——AgentEngine 主路径用 request.getEpoch()。
     *
     * <p>epoch = request.getEpoch()(Repository.execute 时注入的 memoryStore.currentEpoch());
     * clearUserData 后 request.getEpoch() 与 stamped.epoch 不一致 → drain 时 drop。
     */
    public void offer(String sessionId, Steer steer, long epoch) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        if (steer == null) throw new IllegalArgumentException("steer 不能为空");
        Queue<StampedSteer> queue = queues.get(sessionId);
        if (queue == null) {
            Queue<StampedSteer> fresh = new ConcurrentLinkedQueue<>();
            Queue<StampedSteer> existing = queues.putIfAbsent(sessionId, fresh);
            queue = existing == null ? fresh : existing;
        }
        queue.offer(new StampedSteer(steer, epoch));
        Log.i(TAG, "[Steer] offered session=" + sessionId + " type=" + steer.getType()
                + " payloadChars=" + safeLength(steer.getPayload())
                + " stampedEpoch=" + epoch
                + " queueSize=" + queue.size());
    }

    /**
     * 取出并清空指定 session 队列的全部 Steer,按 FIFO 返回。空队列返回空 List。
     *
     * <p>旧 API(不传 epoch)——全量 drain,不 drop stale。历史测试零回归。
     */
    public List<Steer> drain(String sessionId) {
        if (sessionId == null) return Collections.emptyList();
        Queue<StampedSteer> queue = queues.get(sessionId);
        if (queue == null || queue.isEmpty()) return Collections.emptyList();
        List<Steer> drained = new ArrayList<>();
        StampedSteer stamped;
        while ((stamped = queue.poll()) != null) {
            drained.add(stamped.inner);
        }
        Log.d(TAG, "[Steer] drained (legacy) session=" + sessionId + " count=" + drained.size());
        return drained;
    }

    /**
     * epoch-gated drain——AgentEngine 主路径用。
     *
     * <p>遍历 StampedSteer,stamped.epoch &lt; currentEpoch 的视为 stale → drop + audit
     * (走 {@link StaleSteerHandler#onStaleSteerDropped});stamped.epoch &gt;= currentEpoch
     * 的 unwrap 加入返回 List。
     *
     * @param sessionId 会话 ID
     * @param requestEpoch AgentRequest.getEpoch()(AgentEngine 主路径注入)
     * @return retained Steer List(stale 已被过滤)
     */
    public List<Steer> drain(String sessionId, long requestEpoch) {
        if (sessionId == null) return Collections.emptyList();
        Queue<StampedSteer> queue = queues.get(sessionId);
        if (queue == null || queue.isEmpty()) return Collections.emptyList();
        List<Steer> retained = new ArrayList<>();
        int dropped = 0;
        StampedSteer stamped;
        while ((stamped = queue.poll()) != null) {
            if (stamped.epoch < requestEpoch) {
                dropped++;
                handleStaleDrop(sessionId, stamped);
            } else {
                retained.add(stamped.inner);
            }
        }
        Log.d(TAG, "[Steer] drained (epoch-gated) session=" + sessionId
                + " retained=" + retained.size()
                + " dropped=" + dropped
                + " requestEpoch=" + requestEpoch);
        return retained;
    }

    private void handleStaleDrop(String sessionId, StampedSteer stamped) {
        Steer steer = stamped.inner;
        Log.w(TAG, "[Steer] STALE DROP session=" + sessionId
                + " type=" + steer.getType()
                + " payloadChars=" + safeLength(steer.getPayload())
                + " stampedEpoch=" + stamped.epoch
                + " < currentEpoch=" + currentEpoch.get()
                + " — clearUserData 已发生,陈旧指令被丢弃");
        StaleSteerHandler handler = this.staleHandler;
        if (handler != null) {
            try {
                handler.onStaleSteerDropped(sessionId, steer, stamped.epoch, currentEpoch.get());
            } catch (Exception ex) {
                Log.w(TAG, "[Steer] staleHandler.onStaleSteerDropped FAILED cause="
                        + ex.getMessage());
            }
        }
    }

    /**
     * 只查队列中是否含 DEFER,**不动队列**。
     *
     * <p>peek 不参与 epoch gate——epoch gate 只在 drain 时生效。
     * peek 看到的是 stale DEFER 仍返回 true(由 caller 决定是否提前 abort);
     * 但 AgentEngine 主路径 hasDeferredSteer → DEFER 终止本身就会让任务结束,
     * stale DEFER 让任务结束 = 等价 clearUserData 语义(用户数据已清,任务也该结束)。
     */
    public boolean peekDeferred(String sessionId) {
        if (sessionId == null) return false;
        Queue<StampedSteer> queue = queues.get(sessionId);
        if (queue == null) return false;
        for (StampedSteer s : queue) {
            if (s.inner.getType() == Steer.Type.DEFER) return true;
        }
        return false;
    }

    /** 测试 / 监控用:返回当前队列长度但不取出。 */
    public int pendingCount(String sessionId) {
        if (sessionId == null) return 0;
        Queue<StampedSteer> queue = queues.get(sessionId);
        return queue == null ? 0 : queue.size();
    }

    /**
     * 清空所有 session 队列——{@link com.matrix.agent.task.AgentRuntimeRepository#clearUserData()} 调用。
     */
    public void clearAll() {
        queues.clear();
        Log.i(TAG, "[Steer] cleared all sessions");
    }

    private static int safeLength(String value) {
        return value == null ? 0 : value.length();
    }
}
