package com.matrix.agent.launcher.data;

import android.os.SystemClock;
import androidx.lifecycle.Observer;
import com.matrix.agent.api.interaction.*;
import com.matrix.agent.client.OverlayInteractionManager;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** One visibility-scoped lease, with connection generations and at most one pending UI sample. */
public final class OverlayPointerClient implements OverlayPointerSource, AutoCloseable {
    private final LauncherHostGateway gateway;
    private final ExecutorService calls;
    private final Observer<Integer> connection = ignored -> reconnect();
    private Watch watch;
    private volatile Session session;
    private ScheduledFuture<?> renewal;
    private boolean closed;
    private record Watch(Consumer<OverlayPointerSample> pointer, Runnable unavailable) { }
    private final class Session {
        final Watch target;
        volatile OverlayInteractionManager manager;
        private OverlayPointerSample pending;
        private boolean posted;
        final IOverlayPointerCallback callback = new IOverlayPointerCallback.Stub() {
            @Override public void onPointer(OverlayPointerSample sample) {
                if (!valid() || sample == null || !sample.valid()) return;
                synchronized (Session.this) {
                    pending = sample;
                    if (posted) return;
                    posted = true;
                }
                gateway.dispatchToMain(() -> {
                    OverlayPointerSample value;
                    synchronized (Session.this) { value = pending; pending = null; posted = false; }
                    if (valid() && value != null) {
                        long age = SystemClock.uptimeMillis() - value.uptimeMillis();
                        if (age >= 0 && age <= 250) target.pointer().accept(value);
                        else target.unavailable().run();
                    }
                });
            }
            @Override public void onAvailabilityChanged(boolean available) {
                if (!available) gateway.dispatchToMain(() -> { if (valid()) target.unavailable().run(); });
            }
        };
        Session(Watch target) { this.target = target; }
        boolean valid() { return session == this && gateway.isConnected(); }
    }
    public OverlayPointerClient(LauncherHostGateway gateway, LauncherExecutorRegistry executors) {
        this.gateway = gateway; calls = executors.petInteractions();
        gateway.connectionState().observeForever(connection);
    }
    @Override public Observation observe(Consumer<OverlayPointerSample> pointer, Runnable unavailable) {
        if (closed) return () -> {};
        Watch next = new Watch(pointer, unavailable);
        disconnect(); watch = next; reconnect();
        return () -> { if (watch == next) { disconnect(); watch = null; } };
    }
    private void reconnect() {
        if (closed || watch == null) return;
        if (!gateway.isConnected()) { disconnect(); watch.unavailable().run(); return; }
        if (session != null) return;
        Session next = new Session(watch); session = next;
        renew(next);
    }
    private void renew(Session next) {
        gateway.executeVia(calls, agent -> {
            if (!next.valid()) return false;
            if (next.manager == null) next.manager = agent.getOverlayInteractionManager();
            if (next.manager == null) return false;
            boolean accepted = next.manager.subscribe(next.callback);
            if (!next.valid()) next.manager.unsubscribe(next.callback);
            return accepted;
        }, result -> {
            if (!next.valid()) return;
            if (!result.isSuccess() || !Boolean.TRUE.equals(result.value)) next.target.unavailable().run();
            if (renewal != null) renewal.cancel(false);
            renewal = gateway.schedule(() -> gateway.dispatchToMain(() -> {
                if (next.valid()) renew(next);
            }), 10, TimeUnit.SECONDS);
        });
    }
    private void disconnect() {
        if (renewal != null) renewal.cancel(false);
        renewal = null;
        Session old = session; session = null;
        if (old != null) try {
            calls.execute(() -> { if (old.manager != null) old.manager.unsubscribe(old.callback); });
        } catch (RejectedExecutionException ignored) { /* Host lease expires independently. */ }
    }
    @Override public void close() {
        closed = true; gateway.connectionState().removeObserver(connection); disconnect(); watch = null;
    }
}
