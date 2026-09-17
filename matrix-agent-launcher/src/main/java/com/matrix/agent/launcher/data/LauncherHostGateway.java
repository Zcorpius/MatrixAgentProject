package com.matrix.agent.launcher.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.matrix.agent.api.common.ConnectionState;
import com.matrix.agent.client.MatrixAgent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Launcher-side repository for the published SDK connection.
 *
 * <p>This is deliberately the sole location that knows {@link MatrixAgent}.  ViewModels receive
 * this small port, while Fragments only observe state.  It owns client workers, never borrows a
 * worker from the Host APK, and marshals every result back to the main thread.</p>
 */
public final class LauncherHostGateway {
    private final Context applicationContext;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService calls;
    private final AtomicBoolean connecting = new AtomicBoolean();
    /** Invalidates a create() result and its queued lifecycle callbacks after disconnect(). */
    private final AtomicLong connectionGeneration = new AtomicLong();
    private final ScheduledExecutorService polling;
    private final MutableLiveData<Integer> connectionState =
            new MutableLiveData<>(ConnectionState.CONNECTING);

    @Nullable private volatile MatrixAgent agent;

    public LauncherHostGateway(@NonNull Context context, @NonNull LauncherExecutorRegistry executors) {
        applicationContext = context.getApplicationContext();
        calls = executors.sdkCalls();
        polling = executors.polling();
    }

    public LiveData<Integer> connectionState() { return connectionState; }

    public boolean isConnected() {
        MatrixAgent current = agent;
        return current != null && current.getState() == ConnectionState.CONNECTED;
    }

    /** Idempotently starts (or retries) SDK discovery off the UI thread. */
    public void connect() {
        if (!connecting.compareAndSet(false, true)) return;
        final long generation = connectionGeneration.get();
        try {
            calls.execute(() -> {
                MatrixAgent replacement = null;
                try {
                    replacement = MatrixAgent.create(applicationContext,
                            (value, state) -> publishConnectionState(generation, state));
                    // Activity/ViewModel teardown may race the blocking SDK handshake.  A late
                    // client must never resurrect a deliberately disconnected Launcher.
                    if (generation != connectionGeneration.get()) {
                        replacement.release();
                        return;
                    }
                    MatrixAgent previous = agent;
                    agent = replacement;
                    if (previous != null && previous != replacement) previous.release();
                    publishConnectionState(generation, replacement.getState());
                } catch (RuntimeException failure) {
                    publishConnectionState(generation, ConnectionState.DISCONNECTED);
                } finally {
                    connecting.set(false);
                }
            });
        } catch (RejectedExecutionException unavailable) {
            connecting.set(false);
            publishConnectionState(generation, ConnectionState.DISCONNECTED);
        }
    }

    /** Executes a short SDK call and delivers its value or exception on the main thread. */
    public <T> void execute(@NonNull SdkCall<T> call, @NonNull Consumer<Result<T>> receiver) {
        try {
            calls.execute(() -> {
                Result<T> result;
                MatrixAgent current = agent;
                if (current == null || current.getState() != ConnectionState.CONNECTED) {
                    result = Result.failure(new HostUnavailableException());
                } else {
                    try {
                        result = Result.success(call.run(current));
                    } catch (RuntimeException failure) {
                        result = Result.failure(failure);
                    }
                }
                Result<T> delivery = result;
                main.post(() -> receiver.accept(delivery));
            });
        } catch (RejectedExecutionException unavailable) {
            main.post(() -> receiver.accept(Result.failure(unavailable)));
        }
    }

    public void disconnect() {
        connectionGeneration.incrementAndGet();
        MatrixAgent current = agent;
        agent = null;
        if (current != null) current.release();
        publishConnectionState(connectionGeneration.get(), ConnectionState.DISCONNECTED);
    }

    public ScheduledFuture<?> scheduleWithFixedDelay(@NonNull Runnable task, long delay,
            @NonNull TimeUnit unit) {
        return polling.scheduleWithFixedDelay(task, delay, delay, unit);
    }

    /**
     * Normalizes every SDK callback onto the Launcher main thread.
     *
     * <p>AIDL callbacks do not promise a caller thread.  Repositories use this boundary before
     * touching a ViewModel, so a ViewModel has one serialized state writer instead of carrying
     * ad-hoc locks for Binder callbacks.</p>
     */
    public void dispatchToMain(@NonNull Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            main.post(action);
        }
    }

    private void publishConnectionState(long generation, int state) {
        main.post(() -> {
            if (generation == connectionGeneration.get()) connectionState.setValue(state);
        });
    }

    @FunctionalInterface public interface SdkCall<T> { T run(MatrixAgent agent); }

    public static final class Result<T> {
        @Nullable public final T value;
        @Nullable public final Throwable error;
        private Result(@Nullable T value, @Nullable Throwable error) { this.value = value; this.error = error; }
        public static <T> Result<T> success(@Nullable T value) { return new Result<>(value, null); }
        public static <T> Result<T> failure(@NonNull Throwable error) { return new Result<>(null, error); }
        public boolean isSuccess() { return error == null; }
    }

    public static final class HostUnavailableException extends IllegalStateException {
        public HostUnavailableException() { super("MatrixAgent Host is not connected"); }
    }
}
