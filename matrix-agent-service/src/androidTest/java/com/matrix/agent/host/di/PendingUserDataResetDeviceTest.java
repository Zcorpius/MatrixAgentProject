package com.matrix.agent.host.di;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.matrix.agent.data.db.MatrixDatabase;
import com.matrix.agent.data.db.SessionHistoryEntity;
import com.matrix.agent.data.memory.MemoryScope;
import com.matrix.agent.data.memory.RoomMemoryMigrator;
import com.matrix.agent.data.memory.RoomMemoryStore;
import com.matrix.agent.identity.VehicleZone;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Simulates DB outage and subsequent reset recovery without touching product preferences/DB. */
@RunWith(AndroidJUnit4.class)
public final class PendingUserDataResetDeviceTest {
    private static final String PREFIX = "memory-reset-device-test-";
    private Context realContext;
    private Context isolatedContext;
    private MatrixDatabase database;

    @Before public void setUp() {
        realContext = ApplicationProvider.getApplicationContext();
        isolatedContext = new ContextWrapper(realContext) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences(PREFIX + name, mode);
            }
        };
        database = Room.inMemoryDatabaseBuilder(realContext, MatrixDatabase.class)
                .allowMainThreadQueries().build();
    }

    @After public void tearDown() {
        if (database != null) database.close();
        if (realContext != null) {
            realContext.deleteSharedPreferences(PREFIX + "matrix_agent_reset_state");
            realContext.deleteSharedPreferences(PREFIX + RoomMemoryMigrator.SP_FILE_NAME);
        }
    }

    @Test public void pendingResetBlocksUnavailableDatabaseAndErasesOnRecovery() {
        RoomMemoryStore store = new RoomMemoryStore(database.memoryRecordDao(),
                database::runInTransaction);
        MemoryScope driver = new MemoryScope("demo-driver", VehicleZone.DRIVER);
        assertTrue(store.putPreferenceChecked(driver, "preferred_temperature", "24",
                store.currentEpoch()));
        SessionHistoryEntity episode = new SessionHistoryEntity();
        episode.userId = "demo-driver";
        episode.zone = "driver";
        episode.sessionId = "reset-test-session";
        episode.startedAtMillis = System.currentTimeMillis();
        episode.finalState = "SUCCEEDED";
        episode.trajectoryJson = "{}";
        database.sessionHistoryDao().insert(episode);
        isolatedContext.getSharedPreferences(RoomMemoryMigrator.SP_FILE_NAME, Context.MODE_PRIVATE)
                .edit().putString("demo-driver:old", "old secret").commit();

        PendingUserDataReset.mark(isolatedContext);
        try {
            PendingUserDataReset.recoverIfNeeded(isolatedContext, null, Runnable::run);
            org.junit.Assert.fail("encrypted database outage must keep reset pending");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("unavailable"));
        }
        assertEquals("24", store.getPreference(driver, "preferred_temperature"));

        PendingUserDataReset.recoverIfNeeded(isolatedContext, database, Runnable::run);
        assertNull(store.getPreference(driver, "preferred_temperature"));
        assertTrue(database.sessionHistoryDao().queryByUserZone("demo-driver", "driver", 10)
                .isEmpty());
        assertTrue(isolatedContext.getSharedPreferences(RoomMemoryMigrator.SP_FILE_NAME,
                Context.MODE_PRIVATE)
                .getAll().isEmpty());
        assertFalse(isolatedContext.getSharedPreferences("matrix_agent_reset_state",
                Context.MODE_PRIVATE).getBoolean("pending", false));
        RoomMemoryStore reopened = new RoomMemoryStore(database.memoryRecordDao(),
                database::runInTransaction);
        assertEquals(1L, reopened.currentEpoch());
        assertFalse(store.putPreferenceChecked(driver, "preferred_temperature", "25", 0L));
    }
    @Test public void recoveryTransactionFailureDegradesThenNextStartupRecovers() {
        RoomMemoryStore store = new RoomMemoryStore(database, null);
        MemoryScope scope = new MemoryScope("demo-driver", VehicleZone.DRIVER);
        assertTrue(store.putPreferenceChecked(scope, "preferred_temperature", "24", 0));
        database.getOpenHelper().getWritableDatabase().execSQL(
                "CREATE TRIGGER reset_failure BEFORE DELETE ON memory_record BEGIN SELECT RAISE(ABORT,'injected'); END");
        PendingUserDataReset.mark(isolatedContext);
        MatrixDatabase admitted = PendingUserDataReset.databaseAfterRecovery(isolatedContext, database, Runnable::run);
        assertNull(admitted);
        MemoryRuntimeGraph graph = new MemoryRuntimeGraph(isolatedContext, admitted,
                new com.matrix.agent.session.SessionManager(), Runnable::run);
        assertTrue(graph.isDegraded());
        assertNull(graph.store().getPreference(scope, "preferred_temperature"));
        assertTrue(isolatedContext.getSharedPreferences("matrix_agent_reset_state", Context.MODE_PRIVATE)
                .getBoolean("pending", false));
        assertEquals("24", store.getPreference(scope, "preferred_temperature"));
        assertEquals(0L, new RoomMemoryStore(database, null).currentEpoch());
        database.getOpenHelper().getWritableDatabase().execSQL("DROP TRIGGER reset_failure");
        assertEquals(database, PendingUserDataReset.databaseAfterRecovery(isolatedContext, database, Runnable::run));
        MemoryRuntimeGraph recovered = new MemoryRuntimeGraph(isolatedContext, database,
                new com.matrix.agent.session.SessionManager(), Runnable::run);
        assertFalse(recovered.isDegraded());
        assertNull(recovered.store().getPreference(scope, "preferred_temperature"));
        assertFalse(isolatedContext.getSharedPreferences("matrix_agent_reset_state", Context.MODE_PRIVATE)
                .getBoolean("pending", false));
    }

    @Test public void rejectedRecoveryWorkerKeepsTombstoneAndDisablesPersistence() {
        PendingUserDataReset.mark(isolatedContext);
        assertNull(PendingUserDataReset.databaseAfterRecovery(isolatedContext, database,
                task -> { throw new java.util.concurrent.RejectedExecutionException("injected"); }));
        assertTrue(isolatedContext.getSharedPreferences("matrix_agent_reset_state", Context.MODE_PRIVATE)
                .getBoolean("pending", false));
    }

    @Test public void startupPrunesUnrecallableMigrationStubsEvenOnExistingV14() {
        SessionHistoryEntity row = new SessionHistoryEntity();
        row.userId = "demo-driver";
        row.zone = "driver";
        row.sessionId = "old-v14";
        row.trajectoryJson = "{\"legacySanitized\":true}";
        database.sessionHistoryDao().insert(row);
        MemoryRuntimeGraph graph = new MemoryRuntimeGraph(isolatedContext, database,
                new com.matrix.agent.session.SessionManager(), Runnable::run);
        assertFalse(graph.isDegraded());
        assertTrue(database.sessionHistoryDao().queryByUserZone("demo-driver", "driver", 10).isEmpty());
    }

    @Test public void timedOutRecoveryCannotAcknowledgeThePendingResetLater() {
        PendingUserDataReset.mark(isolatedContext);
        java.util.concurrent.atomic.AtomicReference<Runnable> queued = new java.util.concurrent.atomic.AtomicReference<>();
        assertNull(PendingUserDataReset.databaseAfterRecovery(isolatedContext, database, queued::set));
        assertTrue(isolatedContext.getSharedPreferences("matrix_agent_reset_state", Context.MODE_PRIVATE)
                .getBoolean("pending", false));
        // The delayed executor finally accepts the cancelled task: no completion acknowledgement.
        queued.get().run();
        assertTrue(isolatedContext.getSharedPreferences("matrix_agent_reset_state", Context.MODE_PRIVATE)
                .getBoolean("pending", false));
        assertEquals(database, PendingUserDataReset.databaseAfterRecovery(isolatedContext, database, Runnable::run));
    }

}
