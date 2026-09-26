package com.matrix.agent.host.rpc;

import android.content.Context;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import com.matrix.agent.api.common.MatrixServiceConstants;
import com.matrix.agent.api.handoff.*;
import com.matrix.agent.handoff.HandoffCoordinator;
import com.matrix.agent.identity.ActorUsers;
import java.util.Arrays;

/** Narrow Launcher-only Binder boundary; it never waits for the UI or a tool worker. */
public final class ExternalAppHandoffServiceStub extends IExternalAppHandoffService.Stub {
    private final Context context;
    private final HandoffCoordinator coordinator;
    private IBinder registered;
    private IBinder.DeathRecipient death;
    public ExternalAppHandoffServiceStub(Context context, HandoffCoordinator coordinator) {
        this.context = context; this.coordinator = coordinator;
    }
    private CallerContext caller() {
        CallerContext caller = CallerContext.capture(context);
        caller.enforceTrusted(context);
        String[] packages = context.getPackageManager().getPackagesForUid(caller.uid);
        if (caller.userId != Process.myUid() / 100_000 || packages == null
                || !Arrays.asList(packages).contains(MatrixServiceConstants.LAUNCHER_PACKAGE)) {
            throw new SecurityException("handoff requires Launcher in the serving Android user");
        }
        return caller;
    }
    @Override public synchronized int registerCallback(IExternalAppHandoffCallback callback) {
        CallerContext caller = caller();
        if (callback == null) return HandoffProtocol.STALE_REGISTRATION;
        IBinder binder = callback.asBinder();
        if (!binder.equals(registered)) {
            // Invalidate the previous owner even if the replacement is already dead. Otherwise
            // unlinking its death recipient could leave an unmonitored old registration alive.
            coordinator.clearRegistration();
            unlink();
            IBinder.DeathRecipient recipient = () -> coordinator.unregister(binder, caller.uid);
            try { binder.linkToDeath(recipient, 0); }
            catch (RemoteException dead) { return HandoffProtocol.STALE_REGISTRATION; }
            registered = binder; death = recipient;
        }
        coordinator.register(new HandoffCoordinator.Registration(binder, caller.uid, caller.userId,
                ActorUsers.USER_DRIVER, "DRIVER", new HandoffCoordinator.Endpoint() {
            @Override public void request(ExternalAppHandoffRequest request) throws RemoteException {
                callback.onHandoffRequested(request);
            }
            @Override public void launchFinished(String id, int result, long elapsed) throws RemoteException {
                callback.onLaunchAttemptFinished(id, result, elapsed);
            }
            @Override public void activity(ExternalUiActivitySnapshot snapshot) throws RemoteException {
                callback.onExternalUiActivityChanged(snapshot);
            }
        }));
        return HandoffProtocol.ACCEPTED;
    }
    @Override public synchronized void unregisterCallback(IExternalAppHandoffCallback callback) {
        CallerContext caller = caller();
        if (callback == null) return;
        coordinator.unregister(callback.asBinder(), caller.uid);
        if (callback.asBinder().equals(registered)) unlink();
    }
    @Override public int acknowledgeHandoff(IExternalAppHandoffCallback callback, String id,
            int result, int reason) {
        CallerContext caller = caller();
        return callback == null ? HandoffProtocol.STALE_REGISTRATION
                : coordinator.acknowledge(callback.asBinder(), caller.uid, id, result, reason);
    }
    private void unlink() {
        if (registered != null && death != null) registered.unlinkToDeath(death, 0);
        registered = null; death = null;
    }
    public synchronized void close() { unlink(); coordinator.clearRegistration(); }
}
