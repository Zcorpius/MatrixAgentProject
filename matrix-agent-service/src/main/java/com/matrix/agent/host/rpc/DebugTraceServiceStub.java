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
 * <p>Host 侧 {@code BuildConfig.MATRIX_DEBUG_TRACE_UI=false} 时拒绝注册且不产生事件
 * （契约 1：双侧自门控，不跨 Binder 查对方）。注册即回放有界 ring buffer 快照，
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
                emitter.unsubscribe(this.bridgeOf(callback));
                Log.w(TAG, "[DebugTrace] 客户端死亡，摘除订阅");
            }
        };
        bridges.put(callback, bridge); // 见字段——用 Map 维持 callback→bridge 映射
        emitter.subscribe(bridge);
        List<DebugTraceWireEvent> replay = new ArrayList<>();
        for (DebugTraceEvent event : emitter.snapshot()) {
            replay.add(toWire(event));
        }
        Log.i(TAG, "[DebugTrace] 订阅建立 replay=" + replay.size());
        return replay;
    }

    @Override
    public void unsubscribe(IDebugTraceCallback callback) {
        if (callback == null || emitter == null) {
            return;
        }
        Consumer<DebugTraceEvent> bridge = bridges.remove(callback);
        if (bridge != null) {
            emitter.unsubscribe(bridge);
        }
    }

    // ---------------------------------------------------------------- 内部

    /** callback → bridge 映射（unsubscribe 时还原）。 */
    private final java.util.concurrent.ConcurrentHashMap<IDebugTraceCallback,
            Consumer<DebugTraceEvent>> bridges =
            new java.util.concurrent.ConcurrentHashMap<>();

    private Consumer<DebugTraceEvent> bridgeOf(IDebugTraceCallback callback) {
        return bridges.get(callback);
    }

    private static DebugTraceWireEvent toWire(DebugTraceEvent event) {
        return new DebugTraceWireEvent(event.timestampMs, event.phase, event.taskId,
                event.traceId, event.partIndex, event.partCount, event.payload);
    }
}
