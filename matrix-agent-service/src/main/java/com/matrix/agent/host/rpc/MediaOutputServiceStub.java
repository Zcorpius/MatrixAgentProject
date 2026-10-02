package com.matrix.agent.host.rpc;

import android.content.Context;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;

import com.matrix.agent.api.common.MatrixServiceConstants;
import com.matrix.agent.api.media.IMediaOutputCallback;
import com.matrix.agent.api.media.IMediaOutputService;
import com.matrix.agent.api.media.MediaOutputSnapshot;
import com.matrix.agent.platform.media.MediaOutputController;

import java.util.Arrays;

/** Signature-protected Binder facade; audio policy remains in the Host process. */
public final class MediaOutputServiceStub extends IMediaOutputService.Stub
        implements AutoCloseable {
    private final Context context;
    private final RemoteCallbackList<IMediaOutputCallback> callbacks = new RemoteCallbackList<>();
    private final MediaOutputController controller;

    public MediaOutputServiceStub(Context context) {
        this.context = context.getApplicationContext();
        controller = new MediaOutputController(this.context, this::notifyChanged);
    }

    private void authorize() {
        CallerContext caller = CallerContext.capture(context);
        caller.enforceTrusted(context);
        String[] packages = context.getPackageManager().getPackagesForUid(caller.uid);
        if (packages == null || !Arrays.asList(packages).contains(
                MatrixServiceConstants.LAUNCHER_PACKAGE)
                || caller.userId != android.os.Process.myUid() / 100_000) {
            throw new SecurityException("Media output control requires the local Matrix Launcher");
        }
    }

    @Override public MediaOutputSnapshot getSnapshot() {
        authorize();
        return controller.snapshot();
    }

    @Override public MediaOutputSnapshot select(int output) {
        authorize();
        return controller.select(output);
    }

    @Override public MediaOutputSnapshot subscribe(IMediaOutputCallback callback) {
        authorize();
        if (callback == null) throw new IllegalArgumentException("callback required");
        callbacks.register(callback);
        return controller.snapshot();
    }

    @Override public void unsubscribe(IMediaOutputCallback callback) {
        authorize();
        if (callback != null) callbacks.unregister(callback);
    }

    private void notifyChanged(MediaOutputSnapshot snapshot) {
        int count = callbacks.beginBroadcast();
        try {
            for (int index = 0; index < count; index++) {
                try { callbacks.getBroadcastItem(index).onChanged(snapshot); }
                catch (RemoteException ignored) { /* RemoteCallbackList removes dead clients. */ }
            }
        } finally {
            callbacks.finishBroadcast();
        }
    }

    @Override public void close() {
        controller.close();
        callbacks.kill();
    }
}
