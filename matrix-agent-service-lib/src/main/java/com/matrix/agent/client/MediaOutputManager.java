package com.matrix.agent.client;

import android.os.IBinder;
import android.os.RemoteException;

import com.matrix.agent.api.media.IMediaOutputCallback;
import com.matrix.agent.api.media.IMediaOutputService;
import com.matrix.agent.api.media.MediaOutputSnapshot;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** System MEDIA output control. Synchronous methods must be called off the UI thread. */
public final class MediaOutputManager extends MatrixManagerBase {
    private volatile IMediaOutputService service;
    private final CopyOnWriteArrayList<Subscription> subscriptions = new CopyOnWriteArrayList<>();

    MediaOutputManager(MatrixAgent agent, IBinder binder) {
        super(agent, binder);
        service = IMediaOutputService.Stub.asInterface(binder);
    }

    @FunctionalInterface private interface Call<T> {
        T run(IMediaOutputService service) throws RemoteException;
    }

    private <T> T call(Call<T> action) {
        IMediaOutputService current = service;
        if (current == null) throw new IllegalStateException("媒体输出服务未连接");
        try { return action.run(current); }
        catch (RemoteException failure) {
            handleRemoteException(failure);
            throw new IllegalStateException("媒体输出服务连接中断", failure);
        }
    }

    public MediaOutputSnapshot getSnapshot() { return call(IMediaOutputService::getSnapshot); }
    public MediaOutputSnapshot select(int output) { return call(s -> s.select(output)); }

    @FunctionalInterface public interface Listener {
        void onChanged(MediaOutputSnapshot snapshot);
    }

    public Subscription subscribe(Listener listener) {
        if (listener == null) throw new IllegalArgumentException("listener required");
        Subscription subscription = new Subscription(listener);
        subscriptions.add(subscription);
        try { subscription.register(); }
        catch (RuntimeException failure) {
            subscriptions.remove(subscription);
            throw failure;
        }
        return subscription;
    }

    public final class Subscription implements AutoCloseable {
        private final Listener listener;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final IMediaOutputCallback callback = new IMediaOutputCallback.Stub() {
            @Override public void onChanged(MediaOutputSnapshot snapshot) {
                if (snapshot != null) deliver(snapshot);
            }
        };
        private MediaOutputSnapshot initial;

        private Subscription(Listener listener) { this.listener = listener; }

        private void deliver(MediaOutputSnapshot snapshot) {
            eventHandler().post(() -> {
                if (!closed.get()) listener.onChanged(snapshot);
            });
        }

        private void register() {
            if (closed.get()) return;
            initial = call(s -> s.subscribe(callback));
            if (initial != null) deliver(initial);
        }

        /** Initial state is also delivered to the listener on the SDK event handler. */
        public MediaOutputSnapshot initial() { return initial; }

        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            subscriptions.remove(this);
            IMediaOutputService current = service;
            if (current != null) {
                try { current.unsubscribe(callback); }
                catch (RemoteException failure) { handleRemoteException(failure); }
            }
        }
    }

    @Override protected void onMatrixServiceDisconnected() {
        service = null;
        serviceBinder = null;
    }

    @Override protected void onMatrixServiceConnected(IBinder binder) {
        serviceBinder = binder;
        service = IMediaOutputService.Stub.asInterface(binder);
        for (Subscription subscription : subscriptions) {
            try { subscription.register(); }
            catch (RuntimeException ignored) { break; }
        }
    }
}
