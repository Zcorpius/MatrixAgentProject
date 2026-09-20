package com.matrix.agent.client;

import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.matrix.agent.api.debug.DebugTraceWireEvent;
import com.matrix.agent.api.debug.IDebugTraceCallback;
import com.matrix.agent.api.debug.IDebugTraceService;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 调试轨迹 Manager（评估 v1.0 §4.3）：订阅 + 实时事件桥。 */
public final class DebugTraceManager extends MatrixManagerBase {

    private static final String TAG = "DebugTrace";

    private volatile IDebugTraceService service;
    private final List<DebugTraceWireEvent> buffer = new CopyOnWriteArrayList<>();
    private Listener listener;

    public interface Listener {
        void onEvent(DebugTraceWireEvent event);
    }

    DebugTraceManager(MatrixAgent matrixAgent, IBinder serviceBinder) {
        super(matrixAgent, serviceBinder);
        service = IDebugTraceService.Stub.asInterface(serviceBinder);
    }

    /** 订阅：返回 Host 回放快照 + 后续实时推送。 */
    public List<DebugTraceWireEvent> subscribe(Listener traceListener) {
        this.listener = traceListener;
        IDebugTraceService s = service;
        if (s == null) return Collections.emptyList();
        try {
            return s.subscribe(new IDebugTraceCallback.Stub() {
                @Override public void onDebugTraceEvent(DebugTraceWireEvent event) {
                    eventHandler().post(() -> {
                        buffer.add(event);
                        Listener l = DebugTraceManager.this.listener;
                        if (l != null) l.onEvent(event);
                    });
                }
            });
        } catch (RemoteException e) {
            handleRemoteException(e);
            return Collections.emptyList();
        }
    }

    public java.util.List<DebugTraceWireEvent> loadHistory(String hostUserMessageId,
            String conversationTaskId, int limit) {
        IDebugTraceService s = service;
        if (s == null) return java.util.Collections.emptyList();
        try {
            java.util.List<DebugTraceWireEvent> result =
                    s.loadHistory(hostUserMessageId, conversationTaskId, limit);
            return result == null ? java.util.Collections.emptyList() : result;
        } catch (RemoteException e) {
            handleRemoteException(e);
            return java.util.Collections.emptyList();
        }
    }

    public void unsubscribe() {
        IDebugTraceService s = service;
        if (s == null) return;
        try {
            s.unsubscribe(null);
        } catch (RemoteException e) {
            handleRemoteException(e);
        }
    }

    public List<DebugTraceWireEvent> buffered() {
        return Collections.unmodifiableList(buffer);
    }

    @Override protected void onMatrixServiceDisconnected() {
        service = null;
    }

    @Override protected void onMatrixServiceConnected(IBinder serviceBinder) {
        service = IDebugTraceService.Stub.asInterface(serviceBinder);
    }
}
