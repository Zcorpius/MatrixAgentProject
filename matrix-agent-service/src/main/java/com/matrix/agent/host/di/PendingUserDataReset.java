package com.matrix.agent.host.di;

import android.content.Context;
import android.content.SharedPreferences;

import com.matrix.agent.conversation.persistence.RoomConversationStore;
import com.matrix.agent.data.db.MatrixDatabase;
import com.matrix.agent.data.db.MemoryRecordEntity;
import com.matrix.agent.data.memory.RoomMemoryMigrator;
import com.matrix.agent.data.memory.RoomMemoryStore;
import com.matrix.agent.identity.ActorUsers;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Durable non-sensitive tombstone for a clear requested while encrypted storage was unavailable. */
final class PendingUserDataReset {
    private static final String FILE = "matrix_agent_reset_state";
    private static final String PENDING = "pending";

    private PendingUserDataReset() { }

    static void mark(Context context) {
        if (!prefs(context).edit().putBoolean(PENDING, true).commit()) {
            throw new IllegalStateException("could not persist pending reset tombstone");
        }
    }

    static void clearMarker(Context context) {
        if (!prefs(context).edit().remove(PENDING).commit()) {
            throw new IllegalStateException("completed reset tombstone could not be removed");
        }
    }

    static void recoverIfNeeded(Context context, MatrixDatabase database, Executor databaseExecutor) {
        if (!prefs(context).getBoolean(PENDING, false)) return;
        if (database == null) throw new IllegalStateException("pending reset: encrypted database unavailable");
        FutureTask<Void> recovery = new FutureTask<>(() -> {
            recoverNow(context, database);
            return null;
        });
        try {
            databaseExecutor.execute(recovery);
            recovery.get(15, TimeUnit.SECONDS);
            // Only the waiting startup thread may acknowledge completion. A timed-out worker
            // may finish later, but must leave the tombstone for the next startup to retry.
            clearMarker(context);
        } catch (InterruptedException interrupted) {
            recovery.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("pending reset recovery interrupted", interrupted);
        } catch (Exception failure) {
            recovery.cancel(true);
            throw new IllegalStateException("pending reset recovery failed", failure);
        }
    }

    /** Startup admission for every persistent domain; failure preserves the retry tombstone. */
    static MatrixDatabase databaseAfterRecovery(Context context, MatrixDatabase database,
            Executor databaseExecutor) {
        if (database == null) return null;
        try {
            recoverIfNeeded(context, database, databaseExecutor);
            return database;
        } catch (RuntimeException failure) {
            Throwable cause = failure;
            while (cause.getCause() != null) cause = cause.getCause();
            android.util.Log.w("MatrixAgent", "[Reset] startup recovery incomplete; persistence disabled"
                    + " cause=" + cause.getClass().getSimpleName());
            return null;
        }
    }

    private static void recoverNow(Context context, MatrixDatabase database) {
        List<String> users = ActorUsers.allKnownUserIds();
        database.runInTransaction(() -> {
            MemoryRecordEntity existing = database.memoryRecordDao().queryByKey(
                    RoomMemoryStore.SYSTEM_USER, RoomMemoryStore.SYSTEM_ZONE,
                    RoomMemoryStore.PREFERENCE_LAYER, RoomMemoryStore.EPOCH_KEY);
            long epoch = existing == null ? 0L : Long.parseLong(existing.value);
            MemoryRecordEntity next = new MemoryRecordEntity();
            next.userId = RoomMemoryStore.SYSTEM_USER;
            next.zone = RoomMemoryStore.SYSTEM_ZONE;
            next.layer = RoomMemoryStore.PREFERENCE_LAYER;
            next.key = RoomMemoryStore.EPOCH_KEY;
            next.value = Long.toString(Math.addExact(epoch, 1L));
            next.capturedAtMs = System.currentTimeMillis();
            database.memoryRecordDao().upsert(next);
            database.memoryRecordDao().upsert(RoomMemoryMigrator.markerEntity());
            for (String user : users) {
                database.memoryRecordDao().deleteByUser(user);
                database.sessionHistoryDao().deleteByUser(user);
                database.trajectoryDao().deleteByUser(user);
                database.auditEventDao().deleteByUser(user);
            }
            new RoomConversationStore(database, Runnable::run).clearForUsers(users);
        });
        if (!RoomMemoryMigrator.clearLegacySharedPreferences(context)) {
            throw new IllegalStateException("pending reset: legacy source could not be removed");
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }
}
