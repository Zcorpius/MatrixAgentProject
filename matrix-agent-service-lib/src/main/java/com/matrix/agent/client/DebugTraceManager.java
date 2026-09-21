package com.matrix.agent.client;

import android.os.IBinder;
import android.os.RemoteException;

import com.matrix.agent.api.debug.DebugTraceWireEvent;
import com.matrix.agent.api.debug.IDebugTraceCallback;
import com.matrix.agent.api.debug.IDebugTraceService;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Internal/debug-only trace manager.
 *
 * <p>服务端即使暴露 Binder，量产 Host 也会返回空；Launcher 仍须由自身 BuildConfig 门控，
 * 因此两端任一侧关门都 fail-closed。此类不被普通业务 API 使用。</p>
 */
public final class DebugTraceManager extends MatrixManagerBase {

    private volatile IDebugTraceService service;

    DebugTraceManager(MatrixAgent matrixAgent, IBinder serviceBinder) {
        super(matrixAgent, serviceBinder);
        service = IDebugTraceService.Stub.asInterface(serviceBinder);
    }

    public List<DebugTraceWireEvent> getHistory(String hostUserMessageId, int limit) {
        IDebugTraceService current = service;
        if (current == null) return Collections.emptyList();
        try {
            List<DebugTraceWireEvent> result = current.getHistory(hostUserMessageId, limit);
            return result == null ? Collections.emptyList() : result;
        } catch (RemoteException error) {
            return handleRemoteException(error, Collections.emptyList());
        }
    }

    public AutoCloseable subscribe(DebugTraceListener listener) {
        Objects.requireNonNull(listener, "listener");
        IDebugTraceCallback callback = new IDebugTraceCallback.Stub() {
            @Override public void onDebugTraceEvent(DebugTraceWireEvent event) {
                if (event != null) eventHandler().post(() -> listener.onEvent(event));
            }
        };
        IDebugTraceService current = service;
        if (current == null) return () -> { };
        try {
            // subscribe 的同步返回值是有界 ring replay；同样切回 SDK event Handler，
            // 保证 replay 与 oneway 实时事件对调用方都在同一线程语义下。
            List<DebugTraceWireEvent> replay = current.subscribe(callback);
            if (replay != null) {
                for (DebugTraceWireEvent event : replay) {
                    if (event != null) eventHandler().post(() -> listener.onEvent(event));
                }
            }
        } catch (RemoteException error) {
            handleRemoteException(error);
        }
        return () -> {
            IDebugTraceService latest = service;
            if (latest == null) return;
            try {
                latest.unsubscribe(callback);
            } catch (RemoteException error) {
                handleRemoteException(error);
            }
        };
    }

    @FunctionalInterface
    public interface DebugTraceListener {
        void onEvent(DebugTraceWireEvent event);
    }

    @Override protected void onMatrixServiceDisconnected() {
        service = null;
    }

    @Override protected void onMatrixServiceConnected(IBinder serviceBinder) {
        service = IDebugTraceService.Stub.asInterface(serviceBinder);
    }
}
