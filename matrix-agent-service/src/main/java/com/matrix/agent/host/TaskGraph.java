package com.matrix.agent.host;

import androidx.annotation.Nullable;

import android.util.Log;

import com.matrix.agent.data.db.MatrixDatabase;
import com.matrix.agent.task.AgentRuntimeRepository;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns durable task admission, recovery and shutdown independently from Android Service wiring. */
final class TaskGraph {
    private static final String TAG = "MatrixAgent";
    @Nullable private final PersistentTaskManager manager;
    private final AtomicBoolean recoveryComplete = new AtomicBoolean(false);

    TaskGraph(@Nullable MatrixDatabase database, AgentRuntimeRepository runtime,
            ExecutorService dispatcher, ExecutorService databaseExecutor) {
        manager = database == null ? null : new PersistentTaskManager(
                new PersistentTaskStore(database), runtime, dispatcher);
        recoverOffMainThread(databaseExecutor);
    }

    /**
     * Room forbids recovery on {@code Service.onCreate}'s main thread.  The encrypted database
     * can take longer than a fixed startup budget to open on a cold device, so a timeout must
     * never permanently disable durable tasks.  Admission stays fail-closed until this one-shot
     * recovery transaction completes; Binder callers then receive {@code SERVICE_NOT_READY}
     * rather than a false acceptance or a cancelled recovery.
     */
    private void recoverOffMainThread(ExecutorService databaseExecutor) {
        if (manager == null) return;
        try {
            databaseExecutor.execute(() -> {
                try {
                    int recovered = manager.recoverAfterProcessDeath();
                    recoveryComplete.set(true);
                    Log.i(TAG, "[TaskGraph] recovery complete interruptedTasks=" + recovered);
                } catch (RuntimeException failure) {
                    // Keep the gate closed. Retrying an unknown recovery can replay or conceal
                    // an interrupted side effect, so recovery requires an explicit Host restart.
                    Log.e(TAG, "[TaskGraph] recovery failed; durable task admission stays closed",
                            failure);
                }
            });
        } catch (RejectedExecutionException unavailable) {
            Log.e(TAG, "[TaskGraph] recovery was rejected; durable task admission stays closed",
                    unavailable);
        }
    }

    boolean isAvailable() { return manager != null && recoveryComplete.get(); }

    PersistentTaskManager requireManager() {
        if (!isAvailable()) throw new IllegalStateException("persistent task graph unavailable");
        return manager;
    }

    void shutdown() {
        if (manager != null) manager.cancelAll();
    }
}
