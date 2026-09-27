package com.matrix.agent.client;

import android.os.IBinder;
import android.os.RemoteException;
import com.matrix.agent.api.interaction.IOverlayInteractionService;
import com.matrix.agent.api.interaction.IOverlayPointerCallback;

/** Call off main. Only the trusted Launcher may subscribe; callbacks arrive on Binder threads. */
public final class OverlayInteractionManager extends MatrixManagerBase {
    private volatile IOverlayInteractionService service;
    OverlayInteractionManager(MatrixAgent agent, IBinder binder) {
        super(agent, binder); onMatrixServiceConnected(binder);
    }
    public boolean subscribe(IOverlayPointerCallback callback) {
        var current = service;
        if (current == null) return false;
        try { current.subscribe(callback); return true; }
        catch (RemoteException failure) { return handleRemoteException(failure, false); }
    }
    public void unsubscribe(IOverlayPointerCallback callback) {
        var current = service;
        if (current == null) return;
        try { current.unsubscribe(callback); }
        catch (RemoteException failure) { handleRemoteException(failure, false); }
    }
    @Override protected void onMatrixServiceDisconnected() { service = null; }
    @Override protected void onMatrixServiceConnected(IBinder binder) {
        service = IOverlayInteractionService.Stub.asInterface(binder);
    }
}
