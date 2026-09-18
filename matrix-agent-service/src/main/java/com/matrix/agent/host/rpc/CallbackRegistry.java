package com.matrix.agent.host.rpc;

import android.os.IBinder;
import android.os.IInterface;
import android.os.RemoteException;

import androidx.annotation.NonNull;

import java.util.concurrent.ConcurrentHashMap;

/** Binder-death-aware callback registry. Identity is the remote binder, never a wrapper object. */
public final class CallbackRegistry<T extends IInterface> {
    public interface Invoker<T> {
        void invoke(T callback) throws RemoteException;
    }

    private final ConcurrentHashMap<IBinder, Entry<T>> callbacks = new ConcurrentHashMap<>();

    public void add(@NonNull T callback) throws RemoteException {
        IBinder binder = callback.asBinder();
        Entry<T> entry = new Entry<>(callback, binder);
        if (callbacks.putIfAbsent(binder, entry) != null) return;
        try {
            binder.linkToDeath(entry.deathRecipient, 0);
        } catch (RemoteException dead) {
            callbacks.remove(binder, entry);
            throw dead;
        }
    }

    public void remove(T callback) {
        if (callback == null) return;
        IBinder binder = callback.asBinder();
        Entry<T> entry = callbacks.remove(binder);
        unlink(binder, entry);
    }

    public void dispatch(@NonNull Invoker<T> invoker) {
        for (java.util.Map.Entry<IBinder, Entry<T>> entry : callbacks.entrySet()) {
            try {
                invoker.invoke(entry.getValue().callback);
            } catch (RemoteException dead) {
                if (callbacks.remove(entry.getKey(), entry.getValue())) {
                    unlink(entry.getKey(), entry.getValue());
                }
            }
        }
    }

    /** Service/domain shutdown path: no remote process should retain a registered recipient. */
    public void clear() {
        for (java.util.Map.Entry<IBinder, Entry<T>> entry : callbacks.entrySet()) {
            if (callbacks.remove(entry.getKey(), entry.getValue())) {
                unlink(entry.getKey(), entry.getValue());
            }
        }
    }

    private void unlink(IBinder binder, Entry<T> entry) {
        if (entry == null) return;
        try {
            binder.unlinkToDeath(entry.deathRecipient, 0);
        } catch (RuntimeException ignored) {
            // Death recipient has already fired or binder is local; map removal is authoritative.
        }
    }

    private final class Entry<C extends IInterface> {
        final C callback;
        final IBinder.DeathRecipient deathRecipient;

        Entry(C callback, IBinder binder) {
            this.callback = callback;
            this.deathRecipient = () -> callbacks.remove(binder);
        }
    }
}
