package com.matrix.agent.data.audit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.matrix.agent.data.db.MatrixDatabase;
import com.matrix.agent.data.db.MemoryRecordDao;
import com.matrix.agent.data.db.MemoryRecordEntity;
import com.matrix.agent.data.db.SessionHistoryDao;
import com.matrix.agent.data.db.SessionHistoryEntity;
import com.matrix.agent.data.db.TrajectoryDao;
import com.matrix.agent.data.db.TrajectoryEntity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * RoomAuditRepository.clearByUserZone 真路径验证(inMemory,不走 SQLCipher)。
 *
 * <p>覆盖:
 * <ul>
 *   <li>5 参构造器(database + 3 DAO)在 runInTransaction 内跨表原子删除。</li>
 *   <li>跨用户 / 跨 zone 隔离:其他 user / 其他 zone 数据保留。</li>
 * </ul>
 *
 * <p>ViewModel clearData UI 行为不在 instrumented 覆盖——已 JVM 测试,
 * 这里聚焦"Room Audit 数据真被删"端到端验证。
 */
@RunWith(AndroidJUnit4.class)
public final class RoomAuditRepositoryClearByUserZoneInstrumentedTest {
    private MatrixDatabase db;
    private TrajectoryDao trajectoryDao;
    private SessionHistoryDao sessionDao;
    private MemoryRecordDao memoryDao;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(context, MatrixDatabase.class)
                .allowMainThreadQueries()
                .build();
        trajectoryDao = db.trajectoryDao();
        sessionDao = db.sessionHistoryDao();
        memoryDao = db.memoryRecordDao();
    }

    @After
    public void tearDown() {
        if (db != null) db.close();
    }

    @Test
    public void clearByUserZoneDeletesAllThreeTablesAtomic() {
        RoomAuditRepository repo = new RoomAuditRepository(db, trajectoryDao, sessionDao, memoryDao);
        // driver zone 3 表
        trajectoryDao.insert(newTrajectory("req-driver", "demo-driver", "DRIVER"));
        sessionDao.insert(newSession("demo-driver", "DRIVER", "session-driver", 100L));
        memoryDao.upsert(newMemory("demo-driver", "DRIVER", "preferred_temperature"));
        // passenger zone 3 表
        trajectoryDao.insert(newTrajectory("req-passenger", "demo-passenger", "PASSENGER"));
        sessionDao.insert(newSession("demo-passenger", "PASSENGER", "session-passenger", 200L));
        memoryDao.upsert(newMemory("demo-passenger", "PASSENGER", "preferred_temperature"));

        ClearOutcome outcome = repo.clearByUserZone("demo-driver", "DRIVER");
        assertEquals("5 参构造路径 + 3 表成功 = SUCCESS",
                ClearOutcome.Status.SUCCESS, outcome.getStatus());
        assertEquals("5 参路径尝试 3 张表",
                3, outcome.getTablesAttempted());
        assertEquals("3 张表都删成功",
                3, outcome.getTablesSucceeded());
        assertTrue("rowsDeleted 应反映 3 表都删了至少 1 行",
                outcome.getRowsDeleted() >= 3);

        // driver zone 3 表全清
        assertTrue("trajectory driver zone 应清空",
                trajectoryDao.queryBySessionScoped(
                        "demo-driver", "DRIVER", "session-driver", 10).isEmpty());
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

    @Test
    public void clearByUserZoneKeepsOtherUsersAndZones() {
        RoomAuditRepository repo = new RoomAuditRepository(db, trajectoryDao, sessionDao, memoryDao);
        // 5 个组合:driver/DRIVER、driver/PASSENGER、passenger/PASSENGER、other-user/DRIVER、other-user/PASSENGER
        trajectoryDao.insert(newTrajectory("r1", "demo-driver", "DRIVER"));
        trajectoryDao.insert(newTrajectory("r2", "demo-driver", "PASSENGER"));
        trajectoryDao.insert(newTrajectory("r3", "demo-passenger", "PASSENGER"));
        trajectoryDao.insert(newTrajectory("r4", "other-user", "DRIVER"));
        trajectoryDao.insert(newTrajectory("r5", "other-user", "PASSENGER"));

        // 只删 demo-driver/DRIVER
        ClearOutcome outcome = repo.clearByUserZone("demo-driver", "DRIVER");
        assertEquals(ClearOutcome.Status.SUCCESS, outcome.getStatus());

        // r1 删
        assertEquals("r1 应被删", null,
                trajectoryDao.queryByRequestScoped("demo-driver", "DRIVER", "r1"));
        // r2-r5 保留
        assertEquals("r2 demo-driver/PASSENGER 不应被删", "r2",
                trajectoryDao.queryByRequestScoped("demo-driver", "PASSENGER", "r2").requestId);
        assertEquals("r3 demo-passenger/PASSENGER 不应被删", "r3",
                trajectoryDao.queryByRequestScoped("demo-passenger", "PASSENGER", "r3").requestId);
        assertEquals("r4 other-user/DRIVER 不应被删", "r4",
                trajectoryDao.queryByRequestScoped("other-user", "DRIVER", "r4").requestId);
        assertEquals("r5 other-user/PASSENGER 不应被删", "r5",
                trajectoryDao.queryByRequestScoped("other-user", "PASSENGER", "r5").requestId);
    }

    @Test
    public void clearByUserZoneFailClosedOnNullArgs() {
        RoomAuditRepository repo = new RoomAuditRepository(db, trajectoryDao, sessionDao, memoryDao);
        // null userId / zone 不应抛,但必须返回 FAILURE
        ClearOutcome nullUser = repo.clearByUserZone(null, "DRIVER");
        assertEquals("null userId 必须返回 FAILURE",
                ClearOutcome.Status.FAILURE, nullUser.getStatus());
        ClearOutcome nullZone = repo.clearByUserZone("demo-driver", null);
        assertEquals("null zone 必须返回 FAILURE",
                ClearOutcome.Status.FAILURE, nullZone.getStatus());
    }

    @Test
    public void singleArgConstructorClearsTrajectoryOnly() {
        // 旧版兼容路径——单参构造器无 database,只删 trajectory 表
        RoomAuditRepository repo = new RoomAuditRepository(trajectoryDao);
        trajectoryDao.insert(newTrajectory("r1", "demo-driver", "DRIVER"));
        sessionDao.insert(newSession("demo-driver", "DRIVER", "s1", 100L));

        ClearOutcome outcome = repo.clearByUserZone("demo-driver", "DRIVER");
        assertEquals("单参路径也应返回 SUCCESS", ClearOutcome.Status.SUCCESS, outcome.getStatus());

        assertTrue("trajectory driver zone 应清空",
                trajectoryDao.queryBySessionScoped(
                        "demo-driver", "DRIVER", "s1", 10).isEmpty());
        // session_history 在单参路径下未删——旧版兼容(测试断言此契约)
        assertEquals("session_history 在单参构造下不参与清理", 1,
                sessionDao.queryByUserZone("demo-driver", "DRIVER", 10).size());
    }

    // ---- 测试数据构造 ----

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
