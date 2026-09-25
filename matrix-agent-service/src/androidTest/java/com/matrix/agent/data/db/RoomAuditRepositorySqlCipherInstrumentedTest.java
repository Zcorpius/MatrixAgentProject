package com.matrix.agent.data.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.matrix.agent.data.audit.ClearOutcome;
import com.matrix.agent.data.audit.RoomAuditRepository;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/** SQLCipher audit persistence on a dedicated file; never opens the production DB. */
@RunWith(AndroidJUnit4.class)
public final class RoomAuditRepositorySqlCipherInstrumentedTest {
    /** 唯一前缀,避免与其他 androidTest case 数据混淆。 */
    private static final String PREFIX = "sqlcipher-" + System.currentTimeMillis() + "-";

    private Context context;
    private String dbName;
    private byte[] passphrase;
    private MatrixDatabase current;

    @org.junit.Before public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        dbName = "audit-review-test-" + java.util.UUID.randomUUID() + ".db";
        passphrase = new byte[32];
        new java.security.SecureRandom().nextBytes(passphrase);
        System.loadLibrary("sqlcipher");
    }

    private MatrixDatabase open() {
        current = androidx.room.Room.databaseBuilder(context, MatrixDatabase.class, dbName)
                .openHelperFactory(new net.zetetic.database.sqlcipher.SupportOpenHelperFactory(passphrase.clone()))
                .allowMainThreadQueries().build();
        return current;
    }

    @After public void tearDown() {
        if (current != null) current.close();
        context.deleteDatabase(dbName);
        java.util.Arrays.fill(passphrase, (byte) 0);
    }

    @Test
    public void sqlCipherPersistSurvivesReopen() throws Exception {
        MatrixDatabase db1 = open();
        TrajectoryDao dao1 = db1.trajectoryDao();
        String driverReq = PREFIX + "persist-driver";
        dao1.insert(newTrajectory(driverReq, "demo-driver", "DRIVER"));

        assertEquals("insert 后立即 query 必须找到",
                driverReq,
                dao1.queryByRequestScoped("demo-driver", "DRIVER", driverReq).requestId);

        // 关闭再打开
        db1.close();

        MatrixDatabase db2 = open();
        TrajectoryDao dao2 = db2.trajectoryDao();

        TrajectoryEntity reloaded = dao2.queryByRequestScoped("demo-driver", "DRIVER", driverReq);
        assertNotNull("reopen 后 driver 行必须仍在(SQLCipher 持久化)", reloaded);
        assertEquals(driverReq, reloaded.requestId);
        db2.close();
    }

    @Test
    public void sqlCipherClearByUserZonePersistsAcrossReopen() {
        MatrixDatabase db1 = open();
        TrajectoryDao dao1 = db1.trajectoryDao();

        String driverReq = PREFIX + "clear-driver";
        String passengerReq = PREFIX + "clear-passenger";
        dao1.insert(newTrajectory(driverReq, "demo-driver", "DRIVER"));
        dao1.insert(newTrajectory(passengerReq, "demo-passenger", "PASSENGER"));

        // 审计两表事务(SQLCipher 真库)
        RoomAuditRepository repo = new RoomAuditRepository(db1, dao1, db1.auditEventDao());
        ClearOutcome outcome = repo.clearByUserZone("demo-driver", "DRIVER");
        assertEquals("clearByUserZone 应 SUCCESS",
                ClearOutcome.Status.SUCCESS, outcome.getStatus());

        // 删除立即验证
        assertNull("driver 应已删",
                dao1.queryByRequestScoped("demo-driver", "DRIVER", driverReq));
        assertNotNull("passenger 应保留",
                dao1.queryByRequestScoped("demo-passenger", "PASSENGER", passengerReq));

        // 关闭再打开,验证删除持久
        db1.close();

        MatrixDatabase db2 = open();
        TrajectoryDao dao2 = db2.trajectoryDao();

        assertNull("reopen 后 driver 行仍空(删除持久)",
                dao2.queryByRequestScoped("demo-driver", "DRIVER", driverReq));
        TrajectoryEntity passengerLeft = dao2.queryByRequestScoped(
                "demo-passenger", "PASSENGER", passengerReq);
        assertNotNull("reopen 后 passenger 行仍在(zone 隔离)", passengerLeft);
        assertEquals(passengerReq, passengerLeft.requestId);
        db2.close();
    }

    @Test
    public void sqlCipherAuditClearPreservesMemoryAcrossReopen() {
        MatrixDatabase db1 = open();

        TrajectoryDao trajectoryDao = db1.trajectoryDao();
        SessionHistoryDao sessionDao = db1.sessionHistoryDao();
        MemoryRecordDao memoryDao = db1.memoryRecordDao();

        String suffix = PREFIX + "atomic";
        trajectoryDao.insert(newTrajectory("traj-" + suffix, "demo-driver", "DRIVER"));
        sessionDao.insert(newSession("demo-driver", "DRIVER", "session-" + suffix, 100L));
        memoryDao.upsert(newMemory("demo-driver", "DRIVER", "key-" + suffix));

        RoomAuditRepository repo = new RoomAuditRepository(db1, trajectoryDao, db1.auditEventDao());
        ClearOutcome outcome = repo.clearByUserZone("demo-driver", "DRIVER");
        assertEquals(ClearOutcome.Status.SUCCESS, outcome.getStatus());
        assertEquals(2, outcome.getTablesAttempted());
        assertEquals(2, outcome.getTablesSucceeded());
        assertEquals(1, outcome.getRowsDeleted());

        // 关闭再打开，验证审计删除持久且记忆仍保留
        db1.close();

        MatrixDatabase db2 = open();

        assertTrue("reopen 后 trajectory driver zone 仍空",
                db2.trajectoryDao().queryBySessionScoped(
                        "demo-driver", "DRIVER", "session-" + suffix, 10).isEmpty());
        assertEquals(1, db2.sessionHistoryDao().queryByUserZone("demo-driver", "DRIVER", 10).size());
        assertEquals(1, db2.memoryRecordDao().queryByUserZoneLayer("demo-driver", "DRIVER", "preference").size());
        db2.close();
    }

    // ---- 测试数据构造(与 inMemory 测试同 schema) ----

    private static TrajectoryEntity newTrajectory(String requestId, String userId, String zone) {
        TrajectoryEntity e = new TrajectoryEntity();
        e.requestId = requestId;
        e.sessionId = "session-" + zone.toLowerCase();
        e.arbitrationKey = "arb-" + requestId;
        e.actor = "ACTOR";
        e.zone = zone;
        e.userId = userId;
        e.startedMs = System.currentTimeMillis();
        e.durationMs = 100L;
        e.iterationCount = 1;
        e.totalToolCalls = 1;
        e.successToolCalls = 1;
        e.stopReason = "COMPLETED";
        e.finalState = "COMPLETED";
        e.trajectoryJson = "[]";
        e.createdAtMs = System.currentTimeMillis();
        return e;
    }

    private static MemoryRecordEntity newMemory(String userId, String zone, String key) {
        MemoryRecordEntity e = new MemoryRecordEntity();
        e.userId = userId;
        e.zone = zone;
        e.layer = "preference";
        e.key = key;
        e.value = "v-" + key;
        e.capturedAtMs = System.currentTimeMillis();
        e.sourceSessionId = "session-" + zone.toLowerCase();
        return e;
    }

    private static SessionHistoryEntity newSession(String userId, String zone,
            String sessionId, long startedAtMillis) {
        SessionHistoryEntity e = new SessionHistoryEntity();
        e.userId = userId;
        e.zone = zone;
        e.sessionId = sessionId;
        e.startedAtMillis = startedAtMillis;
        e.actor = "ACTOR";
        e.finalState = "COMPLETED";
        e.stopReason = "COMPLETED";
        e.durationMs = 50L;
        e.turnCount = 1;
        e.trajectoryJson = "[]";
        return e;
    }
}
