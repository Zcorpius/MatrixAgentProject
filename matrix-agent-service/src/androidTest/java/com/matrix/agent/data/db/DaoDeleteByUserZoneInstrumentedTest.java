package com.matrix.agent.data.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * DAO deleteByUserZone 真实 Room 执行验证(in-memory,不走 SQLCipher)。
 *
 * <p>JVM 契约测试 {@code DaoDeleteByUserZoneContractTest} 只验证方法签名 + fake 行为;
 * 真实 SQL 字符串 / Room 生成 _Impl.java 在 emulator 上首次跑通由本类负责。
 *
 * <p>覆盖新增的 3 个 DAO {@code deleteByUserZone} 方法在 trajectory /
 * memory_record / session_history 三张表上的 zone+user 隔离语义。
 *
 * <p>用 {@link Room#inMemoryDatabaseBuilder} 绕过 SQLCipher + AndroidKeyStore,
 * 每个测试方法独立 database instance(互不污染)。
 */
@RunWith(AndroidJUnit4.class)
public final class DaoDeleteByUserZoneInstrumentedTest {
    private MatrixDatabase db;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(context, MatrixDatabase.class)
                .allowMainThreadQueries()
                .build();
    }

    @After
    public void tearDown() {
        if (db != null) db.close();
    }

    @Test
    public void trajectoryDeleteByUserZoneKeepsOtherZone() {
        TrajectoryDao dao = db.trajectoryDao();
        TrajectoryEntity driverRow = newTrajectory("req-driver", "demo-driver", "DRIVER");
        TrajectoryEntity passengerRow = newTrajectory("req-passenger", "demo-passenger", "PASSENGER");
        TrajectoryEntity driverRow2 = newTrajectory("req-driver-2", "demo-driver", "DRIVER");
        dao.insert(driverRow);
        dao.insert(passengerRow);
        dao.insert(driverRow2);

        int deleted = dao.deleteByUserZone("demo-driver", "DRIVER");
        assertEquals("应删 2 行 driver zone", 2, deleted);

        // passenger zone 行保留
        List<TrajectoryEntity> passengerLeft = dao.queryBySessionScoped(
                "demo-passenger", "PASSENGER", "session-passenger", 10);
        assertEquals("passenger zone 行不应被删", 1, passengerLeft.size());
        // driver zone 行已删
        List<TrajectoryEntity> driverLeft = dao.queryBySessionScoped(
                "demo-driver", "DRIVER", "session-driver", 10);
        assertTrue("driver zone 行应全部删除", driverLeft.isEmpty());
    }

    @Test
    public void memoryRecordDeleteByUserZoneIsolatesZoneAndUser() {
        MemoryRecordDao dao = db.memoryRecordDao();
        dao.upsert(newMemory("demo-driver", "DRIVER", "preference", "preferred_temperature"));
        dao.upsert(newMemory("demo-driver", "DRIVER", "preference", "preferred_seat"));
        dao.upsert(newMemory("demo-passenger", "PASSENGER", "preference", "preferred_temperature"));
        dao.upsert(newMemory("demo-driver", "PASSENGER", "preference", "shared_key"));  // 同 user 异 zone
        dao.upsert(newMemory("other-user", "DRIVER", "preference", "preferred_temperature"));

        int deleted = dao.deleteByUserZone("demo-driver", "DRIVER");
        assertEquals("应删 2 行 (driver/DRIVER/preference/*)", 2, deleted);

        // 同 user 不同 zone 不受影响
        List<MemoryRecordEntity> driverPassengerZone = dao.queryByUserZoneLayer(
                "demo-driver", "PASSENGER", "preference");
        assertEquals("demo-driver/PASSENGER 不应被删", 1, driverPassengerZone.size());
        // 不同 user 不受影响
        List<MemoryRecordEntity> otherUserDriver = dao.queryByUserZoneLayer(
                "other-user", "DRIVER", "preference");
        assertEquals("other-user/DRIVER 不应被删", 1, otherUserDriver.size());
        // 不同 zone 不受影响
        List<MemoryRecordEntity> passengerLeft = dao.queryByUserZoneLayer(
                "demo-passenger", "PASSENGER", "preference");
        assertEquals("demo-passenger/PASSENGER 不应被删", 1, passengerLeft.size());
    }

    @Test
    public void sessionHistoryDeleteByUserZoneIsolatesZoneAndUser() {
        SessionHistoryDao dao = db.sessionHistoryDao();
        dao.insert(newSession("demo-driver", "DRIVER", "session-A", 1_000L));
        dao.insert(newSession("demo-driver", "DRIVER", "session-B", 2_000L));
        dao.insert(newSession("demo-driver", "PASSENGER", "session-C", 3_000L));
        dao.insert(newSession("demo-passenger", "PASSENGER", "session-D", 4_000L));
        dao.insert(newSession("other-user", "DRIVER", "session-E", 5_000L));

        int deleted = dao.deleteByUserZone("demo-driver", "DRIVER");
        assertEquals("应删 2 行 driver/DRIVER", 2, deleted);

        List<SessionHistoryEntity> driverLeft = dao.queryByUserZone(
                "demo-driver", "DRIVER", 10);
        assertTrue("demo-driver/DRIVER 应清空", driverLeft.isEmpty());
        List<SessionHistoryEntity> driverPassengerLeft = dao.queryByUserZone(
                "demo-driver", "PASSENGER", 10);
        assertEquals("demo-driver/PASSENGER 不应被删", 1, driverPassengerLeft.size());
        List<SessionHistoryEntity> passengerLeft = dao.queryByUserZone(
                "demo-passenger", "PASSENGER", 10);
        assertEquals("demo-passenger/PASSENGER 不应被删", 1, passengerLeft.size());
        List<SessionHistoryEntity> otherUserLeft = dao.queryByUserZone(
                "other-user", "DRIVER", 10);
        assertEquals("other-user/DRIVER 不应被删", 1, otherUserLeft.size());
    }

    /**
     * 3 表跨表 transaction 包装:runInTransaction 内调用 3 个 DAO.deleteByUserZone,
     * 验证 Room transaction 包装工作正常 + 全部生效。这覆盖 clearByUserZone
     * 的 Room.runInTransaction 路径,先在 instrumented 验证基本可用性。
     */
    @Test
    public void deleteByUserZoneInTransactionAcrossThreeTables() {
        TrajectoryDao trajectoryDao = db.trajectoryDao();
        SessionHistoryDao sessionDao = db.sessionHistoryDao();
        MemoryRecordDao memoryDao = db.memoryRecordDao();

        trajectoryDao.insert(newTrajectory("req-1", "demo-driver", "DRIVER"));
        sessionDao.insert(newSession("demo-driver", "DRIVER", "session-1", 100L));
        memoryDao.upsert(newMemory("demo-driver", "DRIVER", "preference", "k1"));

        trajectoryDao.insert(newTrajectory("req-2", "demo-passenger", "PASSENGER"));
        sessionDao.insert(newSession("demo-passenger", "PASSENGER", "session-2", 200L));
        memoryDao.upsert(newMemory("demo-passenger", "PASSENGER", "preference", "k2"));

        db.runInTransaction(() -> {
            trajectoryDao.deleteByUserZone("demo-driver", "DRIVER");
            sessionDao.deleteByUserZone("demo-driver", "DRIVER");
            memoryDao.deleteByUserZone("demo-driver", "DRIVER");
        });

        // driver zone 3 表全删
        assertTrue("trajectory driver zone 应清空",
                trajectoryDao.queryBySessionScoped(
                        "demo-driver", "DRIVER", "session-1", 10).isEmpty());
        assertTrue("session_history driver zone 应清空",
                sessionDao.queryByUserZone("demo-driver", "DRIVER", 10).isEmpty());
        assertTrue("memory_record driver zone 应清空",
                memoryDao.queryByUserZoneLayer("demo-driver", "DRIVER", "preference").isEmpty());

        // passenger zone 3 表保留
        assertEquals("trajectory passenger zone 不应被删", 1,
                trajectoryDao.queryBySessionScoped(
                        "demo-passenger", "PASSENGER", "session-passenger", 10).size());
        assertEquals("session_history passenger zone 不应被删", 1,
                sessionDao.queryByUserZone("demo-passenger", "PASSENGER", 10).size());
        assertEquals("memory_record passenger zone 不应被删", 1,
                memoryDao.queryByUserZoneLayer("demo-passenger", "PASSENGER", "preference").size());
    }

    // ---- 测试数据构造辅助 ----

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

    private static MemoryRecordEntity newMemory(String userId, String zone, String layer, String key) {
        MemoryRecordEntity e = new MemoryRecordEntity();
        e.userId = userId;
        e.zone = zone;
        e.layer = layer;
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
