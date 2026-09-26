package com.matrix.agent.launcher.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.matrix.agent.api.common.ConnectionState;
import com.matrix.agent.client.MatrixAgent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Callable;
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
    private static final String TAG = "MatrixAgent";
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
    private int connectionLeases;

    public interface ConnectionLease extends AutoCloseable { @Override void close(); }

    /** Activity and overlay own independent, idempotent handles. No view lifecycle disconnects peers. */
    public synchronized ConnectionLease acquireConnection() {
        connectionLeases++;
        if (connectionLeases == 1 && !isConnected()) connect();
        AtomicBoolean released = new AtomicBoolean();
        return () -> {
            if (!released.compareAndSet(false, true)) return;
            synchronized (LauncherHostGateway.this) {
                if (--connectionLeases == 0) disconnect();
            }
        };
    }

    public LauncherHostGateway(@NonNull Context context, @NonNull LauncherExecutorRegistry executors) {
        applicationContext = context.getApplicationContext();
        calls = executors.sdkCalls();
        polling = executors.polling();
    }

    private synchronized boolean connectionWanted() { return connectionLeases > 0; }

    public LiveData<Integer> connectionState() { return connectionState; }

    public boolean isConnected() {
        MatrixAgent current = agent;
        return current != null && current.getState() == ConnectionState.CONNECTED;
    }

    /**
     * 在当前线程同步执行一次 SDK 调用（已连接检查同 execute）。
     * 仅供自有 lane（如 {@link DraftCommandLane}）在其工作线程上使用；禁止主线程调用。
     */
    public <T> Result<T> callOnCurrentThread(@NonNull SdkCall<T> call) {
        MatrixAgent current = agent;
        if (current == null || current.getState() != ConnectionState.CONNECTED) {
            return Result.failure(new HostUnavailableException());
        }
        try {
            return Result.success(call.run(current));
        } catch (RuntimeException failure) {
            return Result.failure(failure);
        }
    }

    /** Idempotently starts (or retries) SDK discovery off the UI thread. */
    public void connect() {
        if (isConnected() || !connecting.compareAndSet(false, true)) return;
        final long generation = connectionGeneration.get();
        Log.i(TAG, "[LauncherHost] connect begin generation=" + generation);
        try {
            calls.execute(() -> {
                MatrixAgent replacement = null;
                try {
                    replacement = MatrixAgent.create(applicationContext,
                            (value, state) -> publishConnectionState(generation, value, state));
                    // Activity/ViewModel teardown may race the blocking SDK handshake.  A late
                    // client must never resurrect a deliberately disconnected Launcher.
                    if (generation != connectionGeneration.get()) {
                        replacement.release();
                        return;
                    }
                    MatrixAgent previous = agent;
                    agent = replacement;
                    if (previous != null && previous != replacement) previous.release();
                    Log.i(TAG, "[LauncherHost] connect completed generation=" + generation
                            + " state=" + stateName(replacement.getState()));
                    publishConnectionState(generation, replacement, replacement.getState());
                } catch (RuntimeException failure) {
                    Log.w(TAG, "[LauncherHost] connect failed generation=" + generation, failure);
                    publishConnectionState(generation, null, ConnectionState.DISCONNECTED);
                } finally {
                    connecting.set(false);
                    // A new lease can arrive while a released generation is still negotiating.
                    // Its earlier connect() saw connecting=true; resume that requested connection now.
                    if (generation != connectionGeneration.get() && connectionWanted()) {
                        main.post(this::connect);
                    }
                }
            });
        } catch (RejectedExecutionException unavailable) {
            connecting.set(false);
            Log.w(TAG, "[LauncherHost] connect rejected generation=" + generation, unavailable);
            publishConnectionState(generation, null, ConnectionState.DISCONNECTED);
        }
    }

    /** Executes a short SDK call and delivers its value or exception on the main thread. */
    public <T> void execute(@NonNull SdkCall<T> call, @NonNull Consumer<Result<T>> receiver) {
        executeVia(calls, call, receiver);
    }

    /**
     * 专用 lane 变体（草稿命令串行等）：SDK 调用跑在调用方给定的执行器上，结果仍
     * 归一回主线程。lane 自行保证顺序，本方法不做额外排队。
     */
    public <T> void executeVia(@NonNull ExecutorService executor,
            @NonNull SdkCall<T> call, @NonNull Consumer<Result<T>> receiver) {
        try {
            executor.execute(() -> {
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
        publishConnectionState(connectionGeneration.get(), null, ConnectionState.DISCONNECTED);
    }

    public ScheduledFuture<?> scheduleWithFixedDelay(@NonNull Runnable task, long delay,
            @NonNull TimeUnit unit) {
        return polling.scheduleWithFixedDelay(task, delay, delay, unit);
    }

    /** Schedules a bounded one-shot client retry; repository code must retain/cancel the future. */
    public ScheduledFuture<?> schedule(@NonNull Runnable task, long delay, @NonNull TimeUnit unit) {
        return polling.schedule(task, delay, unit);
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

    /**
     * Runs bounded Launcher-owned follow-up work (for example copying an already-authorized
     * Binder file descriptor to an SAF document) off the UI thread.  This is intentionally not
     * an SDK call: it keeps presentation from performing file I/O on the main looper without
     * creating an unowned executor per feature.
     */
    public <T> void executeClientWork(@NonNull Callable<T> work,
            @NonNull Consumer<Result<T>> receiver) {
        try {
            calls.execute(() -> {
                Result<T> result;
                try {
                    result = Result.success(work.call());
                } catch (Exception failure) {
                    result = Result.failure(failure);
                }
                Result<T> delivery = result;
                main.post(() -> receiver.accept(delivery));
            });
        } catch (RejectedExecutionException unavailable) {
            main.post(() -> receiver.accept(Result.failure(unavailable)));
        }
    }

    @NonNull
    public Context applicationContext() {
        return applicationContext;
    }

    /**
     * Publishes only callbacks belonging to the active SDK client.
     *
     * <p>{@link MatrixAgent} dispatches its lifecycle callbacks asynchronously.  In particular,
     * its initial CONNECTING callback can be queued behind a later CONNECTED callback from the
     * worker that completed negotiation.  A generation check alone cannot distinguish those two
     * callbacks because both belong to one connection attempt.  Comparing the source client to
     * the installed client makes the state stream monotonic from the Launcher's perspective and
     * prevents an obsolete CONNECTING from repainting a connected Host.</p>
     */
    private void publishConnectionState(long generation, @Nullable MatrixAgent source, int state) {
        main.post(() -> {
            if (generation != connectionGeneration.get()) return;
            MatrixAgent current = agent;
            if (source != null && current != null && source != current) {
                Log.d(TAG, "[LauncherHost] ignore stale lifecycle state=" + stateName(state)
                        + " generation=" + generation);
                return;
            }
            // A CONNECTING notification may have been posted before the client was installed
            // but execute after its direct CONNECTED publication.  The installed client's
            // current state is authoritative in that narrow ordering window.
            if (state == ConnectionState.CONNECTING && current != null
                    && current.getState() == ConnectionState.CONNECTED) {
                Log.d(TAG, "[LauncherHost] suppress late CONNECTING generation=" + generation);
                return;
            }
            Log.i(TAG, "[LauncherHost] state=" + stateName(state)
                    + " generation=" + generation);
            connectionState.setValue(state);
        });
    }

    @NonNull
    private static String stateName(int state) {
        switch (state) {
            case ConnectionState.DISCONNECTED: return "DISCONNECTED";
            case ConnectionState.CONNECTING: return "CONNECTING";
            case ConnectionState.CONNECTED: return "CONNECTED";
            case ConnectionState.SERVICE_NOT_READY: return "SERVICE_NOT_READY";
            case ConnectionState.PERMISSION_DENIED: return "PERMISSION_DENIED";
            default: return "UNKNOWN(" + state + ')';
        }
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
