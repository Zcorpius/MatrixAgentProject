package com.matrix.agent.client;

import android.os.IBinder;
import android.os.RemoteException;
import com.matrix.agent.api.handoff.*;

/** Connection-scoped presentation protocol. Call off main; callbacks run on Binder threads. */
public final class HandoffManager extends MatrixManagerBase {
    private volatile IExternalAppHandoffService service;
    HandoffManager(MatrixAgent agent, IBinder binder) {
        super(agent, binder);
        onMatrixServiceConnected(binder);
    }
    public int registerCallback(IExternalAppHandoffCallback callback) {
        var current = service;
        if (current == null) return HandoffProtocol.STALE_REGISTRATION;
        try { return current.registerCallback(callback); }
        catch (RemoteException failure) { return handleRemoteException(failure, HandoffProtocol.STALE_REGISTRATION); }
    }
    public void unregisterCallback(IExternalAppHandoffCallback callback) {
        var current = service;
        if (current == null) return;
        try { current.unregisterCallback(callback); }
        catch (RemoteException failure) { handleRemoteException(failure, false); }
    }
    public int acknowledge(IExternalAppHandoffCallback callback, String requestId, int result, int reason) {
        var current = service;
        if (current == null) return HandoffProtocol.STALE_REGISTRATION;
        try { return current.acknowledgeHandoff(callback, requestId, result, reason); }
        catch (RemoteException failure) { return handleRemoteException(failure, HandoffProtocol.STALE_REGISTRATION); }
    }
    @Override protected void onMatrixServiceDisconnected() { service = null; }
    @Override protected void onMatrixServiceConnected(IBinder binder) {
        service = IExternalAppHandoffService.Stub.asInterface(binder);
    }
}
