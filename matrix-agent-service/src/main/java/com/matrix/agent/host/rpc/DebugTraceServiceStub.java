package com.matrix.agent.host.rpc;

import com.matrix.agent.api.debug.DebugTraceWireEvent;
import com.matrix.agent.api.debug.IDebugTraceCallback;
import com.matrix.agent.api.debug.IDebugTraceService;
import com.matrix.agent.debugtrace.DebugTraceEmitter;
import com.matrix.agent.debugtrace.DebugTraceEvent;

import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 调试轨迹 Binder 门面（评估 v1.0 §4.3 契约 3）：独立 append-only 通道。
 *
 * <p>Host 侧 {@code BuildConfig.MATRIX_DEBUG_TRACE_UI=false} 时拒绝注册且不产生<b>UI</b>
 * 事件（脱敏 logcat 事件仍由 Emitter 无条件记录；契约 1 的双侧自门控）。注册即回放有界 ring buffer 快照，
 * 随后实时 oneway 推送——慢/死亡客户端经 RemoteException 摘除，绝不阻塞执行 lane。</p>
 */
public final class DebugTraceServiceStub extends IDebugTraceService.Stub {

    private static final String TAG = "MatrixAgent";

    private final DebugTraceEmitter emitter;
    /** Emitter 是否启用（Host 侧 BuildConfig 门控的快照——false 时本 Stub 是空壳）。 */
    private final boolean hostUiEnabled;

    public DebugTraceServiceStub(DebugTraceEmitter emitter, boolean hostUiEnabled) {
        this.emitter = emitter;
        this.hostUiEnabled = hostUiEnabled;
    }

    @Override
    public List<DebugTraceWireEvent> subscribe(IDebugTraceCallback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback required");
        }
        if (!hostUiEnabled || emitter == null) {
            Log.i(TAG, "[DebugTrace] UI 关闭（BuildConfig 门控），拒绝注册");
            return new ArrayList<>();
        }
        Consumer<DebugTraceEvent> bridge = event -> {
            try {
                callback.onDebugTraceEvent(toWire(event));
            } catch (RemoteException dead) {
                // 死亡客户端：摘除（emit 线程安全——CopyOnWriteArrayList remove）
                emitter.unsubscribe(bridges.remove(callback.asBinder()));
                Log.w(TAG, "[DebugTrace] 客户端死亡，摘除订阅");
            }
        };
        // AIDL Stub 在每次 Binder 调用中可能包装为不同 Java proxy；IBinder 才是稳定
        // 身份。相同 callback 重订阅前先摘掉旧 bridge，杜绝页面重建造成的 fan-out 泄漏。
        Consumer<DebugTraceEvent> previous = bridges.put(callback.asBinder(), bridge);
        if (previous != null) emitter.unsubscribe(previous);
        emitter.subscribe(bridge);
        List<DebugTraceWireEvent> replay = new ArrayList<>();
        for (DebugTraceEvent event : emitter.snapshot()) {
            replay.add(toWire(event));
        }
        Log.i(TAG, "[DebugTrace] 订阅建立 replay=" + replay.size());
        return replay;
    }

    @Override
    public List<DebugTraceWireEvent> getHistory(String hostUserMessageId, int limit) {
        if (!hostUiEnabled || emitter == null) return new ArrayList<>();
        List<DebugTraceWireEvent> result = new ArrayList<>();
        for (DebugTraceEvent event : emitter.history(hostUserMessageId, limit)) {
            result.add(toWire(event));
        }
        return result;
    }

    @Override
    public void unsubscribe(IDebugTraceCallback callback) {
        if (callback == null || emitter == null) {
            return;
        }
        Consumer<DebugTraceEvent> bridge = bridges.remove(callback.asBinder());
        if (bridge != null) {
            emitter.unsubscribe(bridge);
        }
    }

    // ---------------------------------------------------------------- 内部

    /** callback Binder → bridge 映射（unsubscribe 时还原；不能用接口 proxy 的对象身份）。 */
    private final java.util.concurrent.ConcurrentHashMap<android.os.IBinder,
            Consumer<DebugTraceEvent>> bridges =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static DebugTraceWireEvent toWire(DebugTraceEvent event) {
        return new DebugTraceWireEvent(event.timestampMs, event.phase, event.taskId,
                event.traceId, event.eventSequence, event.partIndex, event.partCount,
                event.payload, event.conversationId, event.conversationTaskId,
                event.hostUserMessageId);
    }
}
