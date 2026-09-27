package com.matrix.agent.host.di;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import com.matrix.agent.data.db.MatrixDatabase;
import com.matrix.agent.platform.AndroidKeyStoreMasterKeyProvider;
import com.matrix.agent.platform.MasterKeyProvider;
import com.matrix.agent.data.memory.MemoryStore;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Process-owned persistence admission, shared by cold scheduling and the full Host graph.
 * Database, reset recovery, legacy migration and the epoch store become available together.
 * No HTTP/model/voice/download runtime is constructed on this path.
 */
public final class PersistenceRuntimeGraph implements AutoCloseable {
    private static final String TAG = "MatrixAgent";
    private static volatile PersistenceRuntimeGraph instance;
    private final Context context;
    private final ExecutorService databaseExecutor = lane("matrix-persistence-db", 64);
    private final ExecutorService initializer = lane("matrix-persistence-init", 1);
    private final FutureTask<Admission> admission;

    private PersistenceRuntimeGraph(Context appContext) {
        context = appContext.getApplicationContext();
        admission = new FutureTask<>(this::initialize);
        initializer.execute(admission);
    }

    public static PersistenceRuntimeGraph get(Context context) {
        PersistenceRuntimeGraph result = instance;
        if (result == null) {
            synchronized (PersistenceRuntimeGraph.class) {
                result = instance;
                if (result == null) instance = result = new PersistenceRuntimeGraph(context);
            }
        }
        return result;
    }

    public static void closeIfInitialized() {
        PersistenceRuntimeGraph current = instance;
        if (current != null) current.close();
    }

    /** Timeout never cancels shared initialization or grants temporary plaintext/volatile admission. */
    public Admission await(long timeoutMillis) throws TimeoutException {
        if (timeoutMillis <= 0) throw new TimeoutException("persistence deadline elapsed");
        try {
            return admission.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("persistence initialization interrupted", interrupted);
        } catch (ExecutionException failure) {
            throw new IllegalStateException("persistence initialization failed", failure.getCause());
        }
    }

    public ExecutorService databaseExecutor() { return databaseExecutor; }

    /** A reset tombstone closes admission immediately, including while the full graph is clearing. */
    public boolean resetPending() { return PendingUserDataReset.isPending(context); }

    private Admission initialize() {
        MatrixDatabase database = PendingUserDataReset.databaseAfterRecovery(
                context, createDatabaseSafely(context), databaseExecutor);
        AtomicBoolean degraded = new AtomicBoolean();
        MemoryStore memory = MemoryRuntimeGraph.createStoreSafely(context, database, degraded, databaseExecutor);
        return new Admission(degraded.get() ? null : database, memory);
    }

    public record Admission(@Nullable MatrixDatabase database, MemoryStore memory) {
        public boolean available() { return database != null; }
    }

    private static ExecutorService lane(String name, int capacity) {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(capacity), action -> {
                    Thread thread = new Thread(action, name);
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    /** Only the process owner closes shared workers; a Service or receiver must never close them. */
    @Override public void close() {
        initializer.shutdownNow();
        databaseExecutor.shutdownNow();
    }

    @Nullable
    private static MatrixDatabase createDatabaseSafely(Context appContext) {
        try {
            MasterKeyProvider keyProvider = new AndroidKeyStoreMasterKeyProvider(appContext);
            return MatrixDatabase.getInstance(appContext, keyProvider);
        } catch (Exception error) {
            Log.e(TAG, "[PersistenceRuntimeGraph] encrypted database unavailable; dependent domains "
                    + "stay fail-closed", error);
            return null;
        }
    }
}
