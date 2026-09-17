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
import com.matrix.agent.platform.AndroidKeyStoreMasterKeyProvider;
import com.matrix.agent.platform.MasterKeyProvider;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * RoomAuditRepository.clearByUserZone 在 SQLCipher 真库上的
 * 写入 → 按 scope 删 → close/reopen 持久化端到端验证。
 *
 * <p>对照 {@code RoomAuditRepositoryClearByUserZoneInstrumentedTest}(inMemory,验证 Room schema
 * 与 DAO 行为)与 {@code SmokeTest}(验证 SQLCipher + KeyStore 链路能打开),本测试补第三层:
 * 真实 {@link MatrixDatabase#getInstance(Context, MasterKeyProvider)} SQLCipher 单例,
 * 验证"删除持久"——close 后重开同一加密库,被删的行仍空,保留的行仍在。
 *
 * <p>reopen 路径覆盖核心契约:AndroidKeyStoreMasterKeyProvider 从 SP 加载
 * 同一 AES-GCM 加密的 passphrase,SupportFactory 用同 passphrase 解密 SQLCipher 文件,
 * 不能因 Keystore/SP 偏好持久化失败而"无法读旧库"。
 *
 * <p>测试数据隔离:每个 case 用 unique requestId 前缀(本类时间戳),不依赖数据库初始为空。
 * SmokeTest 等其他 androidTest case 可能已写入 trajectory,本测试只断言自己 insert 的行。
 */
@RunWith(AndroidJUnit4.class)
public final class RoomAuditRepositorySqlCipherInstrumentedTest {
    /** 唯一前缀,避免与其他 androidTest case 数据混淆。 */
    private static final String PREFIX = "sqlcipher-" + System.currentTimeMillis() + "-";

    @After
    public void tearDown() {
        // 清 MatrixDatabase 单例——让后续 androidTest case 重新 getInstance 触发 fresh load。
        // 注意:不删 matrix_agent.db 文件(SQLCipher 数据持久是本测试验证的目标)。
        MatrixDatabase.resetForTest();
    }

    @Test
    public void sqlCipherPersistSurvivesReopen() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        MasterKeyProvider key1 = new AndroidKeyStoreMasterKeyProvider(context);
        MatrixDatabase db1 = MatrixDatabase.getInstance(context, key1);
        TrajectoryDao dao1 = db1.trajectoryDao();
        String driverReq = PREFIX + "persist-driver";
        dao1.insert(newTrajectory(driverReq, "demo-driver", "DRIVER"));

        assertEquals("insert 后立即 query 必须找到",
                driverReq,
                dao1.queryByRequestScoped("demo-driver", "DRIVER", driverReq).requestId);

        // close + resetForTest + 重开
        db1.close();
        MatrixDatabase.resetForTest();

        MasterKeyProvider key2 = new AndroidKeyStoreMasterKeyProvider(context);
        MatrixDatabase db2 = MatrixDatabase.getInstance(context, key2);
        TrajectoryDao dao2 = db2.trajectoryDao();

        TrajectoryEntity reloaded = dao2.queryByRequestScoped("demo-driver", "DRIVER", driverReq);
        assertNotNull("reopen 后 driver 行必须仍在(SQLCipher 持久化)", reloaded);
        assertEquals(driverReq, reloaded.requestId);
        db2.close();
    }

    @Test
    public void sqlCipherClearByUserZonePersistsAcrossReopen() {
        Context context = ApplicationProvider.getApplicationContext();
        MasterKeyProvider key1 = new AndroidKeyStoreMasterKeyProvider(context);
        MatrixDatabase db1 = MatrixDatabase.getInstance(context, key1);
        TrajectoryDao dao1 = db1.trajectoryDao();

        String driverReq = PREFIX + "clear-driver";
        String passengerReq = PREFIX + "clear-passenger";
        dao1.insert(newTrajectory(driverReq, "demo-driver", "DRIVER"));
        dao1.insert(newTrajectory(passengerReq, "demo-passenger", "PASSENGER"));

        // 用 RoomAuditRepository 5 参路径(SQLCipher 真库 + transaction)
        RoomAuditRepository repo = new RoomAuditRepository(db1, dao1,
                db1.sessionHistoryDao(), db1.memoryRecordDao());
        ClearOutcome outcome = repo.clearByUserZone("demo-driver", "DRIVER");
        assertEquals("clearByUserZone 应 SUCCESS",
                ClearOutcome.Status.SUCCESS, outcome.getStatus());

        // 删除立即验证
        assertNull("driver 应已删",
                dao1.queryByRequestScoped("demo-driver", "DRIVER", driverReq));
        assertNotNull("passenger 应保留",
                dao1.queryByRequestScoped("demo-passenger", "PASSENGER", passengerReq));

        // close + resetForTest + 重开,验证删除持久
        db1.close();
        MatrixDatabase.resetForTest();

        MasterKeyProvider key2 = new AndroidKeyStoreMasterKeyProvider(context);
        MatrixDatabase db2 = MatrixDatabase.getInstance(context, key2);
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
    public void sqlCipherThreeTablesClearedAtomicallyPersists() {
        Context context = ApplicationProvider.getApplicationContext();
        MasterKeyProvider key1 = new AndroidKeyStoreMasterKeyProvider(context);
        MatrixDatabase db1 = MatrixDatabase.getInstance(context, key1);

        TrajectoryDao trajectoryDao = db1.trajectoryDao();
        SessionHistoryDao sessionDao = db1.sessionHistoryDao();
        MemoryRecordDao memoryDao = db1.memoryRecordDao();

        String suffix = PREFIX + "atomic";
        trajectoryDao.insert(newTrajectory("traj-" + suffix, "demo-driver", "DRIVER"));
        sessionDao.insert(newSession("demo-driver", "DRIVER", "session-" + suffix, 100L));
        memoryDao.upsert(newMemory("demo-driver", "DRIVER", "key-" + suffix));

        RoomAuditRepository repo = new RoomAuditRepository(db1, trajectoryDao, sessionDao, memoryDao);
        ClearOutcome outcome = repo.clearByUserZone("demo-driver", "DRIVER");
        assertEquals(ClearOutcome.Status.SUCCESS, outcome.getStatus());
        assertEquals("3 张表都尝试", 3, outcome.getTablesAttempted());
        assertEquals("3 张表都成功", 3, outcome.getTablesSucceeded());
        assertTrue("3 表都删了至少 1 行", outcome.getRowsDeleted() >= 3);

        // close + resetForTest + 重开,验证 3 表删除持久
        db1.close();
        MatrixDatabase.resetForTest();

        MasterKeyProvider key2 = new AndroidKeyStoreMasterKeyProvider(context);
        MatrixDatabase db2 = MatrixDatabase.getInstance(context, key2);

        assertTrue("reopen 后 trajectory driver zone 仍空",
                db2.trajectoryDao().queryBySessionScoped(
                        "demo-driver", "DRIVER", "session-" + suffix, 10).isEmpty());
        assertTrue("reopen 后 session_history driver zone 仍空",
                db2.sessionHistoryDao().queryByUserZone("demo-driver", "DRIVER", 10).isEmpty());
        assertTrue("reopen 后 memory_record driver zone 仍空",
                db2.memoryRecordDao().queryByUserZoneLayer("demo-driver", "DRIVER", "preference").isEmpty());
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
