package com.matrix.agent.host.di;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.matrix.agent.data.db.MatrixDatabase;
import com.matrix.agent.data.db.MemoryRecordDao;
import com.matrix.agent.data.memory.EmptyEpisodicMemorySource;
import com.matrix.agent.data.memory.EmptySemanticMemorySource;
import com.matrix.agent.data.memory.EpisodicMemorySource;
import com.matrix.agent.data.memory.EpisodicMemorySourceImpl;
import com.matrix.agent.data.memory.InMemoryMemoryStore;
import com.matrix.agent.data.memory.LegacyPreferenceMemorySource;
import com.matrix.agent.data.memory.MemoryRecaller;
import com.matrix.agent.data.memory.MemoryRouter;
import com.matrix.agent.data.memory.MemoryStore;
import com.matrix.agent.data.memory.MemoryWriter;
import com.matrix.agent.data.memory.RoomMemoryMigrator;
import com.matrix.agent.data.memory.RoomMemoryStore;
import com.matrix.agent.data.memory.RoomMemoryWriter;
import com.matrix.agent.data.memory.SemanticMemorySource;
import com.matrix.agent.data.memory.SemanticMemorySourceImpl;
import com.matrix.agent.data.memory.SessionContextWorkingMemory;
import com.matrix.agent.session.SessionManager;

import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Memory domain composition root.
 *
 * <p>It owns the one-way migration from legacy preferences and makes the degraded mode explicit:
 * if encrypted Room is unavailable, the process receives volatile memory only.  It never falls
 * back to plaintext preferences.</p>
 */
final class MemoryRuntimeGraph {
    private static final String TAG = "MatrixAgent";

    private final MemoryStore store;
    private final MemoryRecaller recaller;
    private final MemoryWriter writer;
    private final boolean degraded;

    MemoryRuntimeGraph(@NonNull Context context, MatrixDatabase database,
            @NonNull SessionManager sessions, Executor databaseExecutor) {
        AtomicBoolean degradedRef = new AtomicBoolean(false);
        store = createStoreSafely(context, database, degradedRef, databaseExecutor);
        degraded = degradedRef.get();

        EpisodicMemorySourceImpl episodicImpl = degraded
                ? null : new EpisodicMemorySourceImpl(database.sessionHistoryDao());
        EpisodicMemorySource episodic = episodicImpl == null
                ? new EmptyEpisodicMemorySource() : episodicImpl;
        SemanticMemorySource semantic = degraded
                ? new EmptySemanticMemorySource()
                : new SemanticMemorySourceImpl(database.memoryRecordDao());
        writer = degraded ? MemoryWriter.NOOP : new RoomMemoryWriter(
                database.sessionHistoryDao(), database.memoryRecordDao(), episodicImpl,
                database::runInTransaction);
        recaller = new MemoryRouter(new SessionContextWorkingMemory(sessions), episodic, semantic,
                new LegacyPreferenceMemorySource(store));
    }

    MemoryStore store() { return store; }
    MemoryRecaller recaller() { return recaller; }
    MemoryWriter writer() { return writer; }
    boolean isDegraded() { return degraded; }

    static MemoryStore createStoreSafely(Context appContext, MatrixDatabase database,
            AtomicBoolean memoryDegradedRef, Executor databaseExecutor) {
        if (database == null) {
            Log.w(TAG, "[MemoryGraph] encrypted store unavailable; using volatile memory");
            memoryDegradedRef.set(true);
            return new DegradedMemoryStore(appContext);
        }
        try {
            MemoryRecordDao dao = database.memoryRecordDao();
            RoomMemoryStore.TransactionRunner txRunner = database::runInTransaction;
            FutureTask<Boolean> migration = new FutureTask<>(() -> {
                if (!RoomMemoryMigrator.fromSharedPreferences(appContext, dao, txRunner).migrate()) return false;
                // Also runs for devices that already upgraded to v14 before this fix.
                txRunner.runInTransaction(() -> database.sessionHistoryDao().deleteSanitizedLegacyRows());
                return true;
            });
            databaseExecutor.execute(migration);
            if (!migration.get(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("legacy memory migration incomplete");
            }
            RoomMemoryStore encryptedStore = new RoomMemoryStore(database, databaseExecutor);
            encryptedStore.currentEpoch(); // fail closed before assembling any persistent sources/writer
            return encryptedStore;
        } catch (Exception error) {
            Log.w(TAG, "[MemoryGraph] encrypted store initialization failed; using volatile memory",
                    error);
            memoryDegradedRef.set(true);
            return new DegradedMemoryStore(appContext);
        }
    }

    private static final class DegradedMemoryStore extends InMemoryMemoryStore {
        private final Context context;

        DegradedMemoryStore(Context context) { this.context = context; }

        @Override public synchronized long clearUserDataAndBump(String user1, String user2) {
            return clearUsersAndBump(java.util.List.of(user1, user2));
        }

        @Override public synchronized long clearUsersAndBump(java.util.List<String> users) {
            PendingUserDataReset.mark(context);
            super.clearUsersAndBump(users);
            throw new IllegalStateException("encrypted storage unavailable; reset pending recovery");
        }
    }
}
