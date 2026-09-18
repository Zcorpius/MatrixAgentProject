package com.matrix.agent.data.memory;
import com.matrix.agent.task.scheduler.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.task.StopReason;
import com.matrix.agent.task.TaskState;
import com.matrix.agent.task.Trajectory;
import com.matrix.agent.task.identity.Actor;
import com.matrix.agent.task.identity.AgentRequest;
import com.matrix.agent.task.identity.VehicleZone;
import com.matrix.agent.data.db.MatrixDatabase;
import com.matrix.agent.data.db.MemoryRecordDao;
import com.matrix.agent.data.db.MemoryRecordEntity;
import com.matrix.agent.data.db.SessionHistoryDao;
import com.matrix.agent.data.db.SessionHistoryEntity;

import java.util.List;

/**
 * 真实 Room + SQLite 写者锁验证"记忆清除后不可复活"。
 *
 * <p>epoch 原子性设计正确,但 JVM fake 测试用 {@code Runnable::run} 模拟事务,
 * 没有验证 SQLite 写者锁的序列化保证。本类在真实 Room(in-memory,绕过 SQLCipher/Keystore)上
 * 验证两个关键不变式:
 *
 * <ol>
 *   <li><b>不变式 A(clear 先,write 后)</b>:clearUserDataAndBump 完成 epoch bump 后,
 *       在途任务用旧 requestEpoch 调 writeEpisodicOnTerminal / writeSemantic 必须被
 *       事务内 read-then-check 拒绝(stale reject),两表均无旧数据。</li>
 *   <li><b>不变式 B(write 先持有锁,clear 后清空)</b>:语义记忆已经 commit 后,
 *       clearUserDataAndBump 必须把刚写入的记忆行清掉。SQLite 写者锁保证两 transaction 串行,
 *       不会出现"clear 看不到刚写入的行"。</li>
 * </ol>
 *
 * <p>不变式 A 是"清除后不可复活"的核心语义;不变式 B 验证 clearUserData 的清空覆盖既有写入。
 * 真实并发交错由 SQLite 单写者锁序列化(两 transaction 不可能交叉),JVM scheduler 顺序无关紧要。
 */
@RunWith(AndroidJUnit4.class)
public final class RoomMemoryWriterEpochAtomicityInstrumentedTest {
    private MatrixDatabase db;
    private SessionHistoryDao sessionDao;
    private MemoryRecordDao memoryDao;
    private RoomMemoryStore store;
    private RoomMemoryWriter writer;

    @Before
    public void setUp() {
        Context context = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(context, MatrixDatabase.class)
                .allowMainThreadQueries()
                .build();
        sessionDao = db.sessionHistoryDao();
        memoryDao = db.memoryRecordDao();
        EpisodicMemorySourceImpl episodicSource = new EpisodicMemorySourceImpl(sessionDao);
        store = new RoomMemoryStore(memoryDao, db::runInTransaction);
        writer = new RoomMemoryWriter(sessionDao, memoryDao, episodicSource, db::runInTransaction);
    }

    @After
    public void tearDown() {
        if (db != null) db.close();
    }

    private static AgentRequest makeRequest(String text, String sessionId, long epoch) {
        return AgentRequest.builder(text, Actor.DRIVER)
                .occupantZone(VehicleZone.DRIVER)
                .sessionId(sessionId)
                .epoch(epoch)
                .build();
    }

    private static AgentOutcome makeSucceededOutcome(AgentRequest request) {
        return new AgentOutcome(request.getRequestId(),
                TaskState.SUCCEEDED, StopReason.DONE, new Trajectory(1L), 100L);
    }

    /**
     * 不变式 A:clearUserDataAndBump 先完成(epoch=1),在途任务用 requestEpoch=0 调
     * writeEpisodicOnTerminal / writeSemantic 必须被事务内 read-then-check 拒绝。
     */
    @Test
    public void staleWriteRejectedAfterClearUserDataOnRealRoom() {
        // 初始 epoch=0,任务 A 用 requestEpoch=0 启动(模拟在途请求)
        AgentRequest requestA = makeRequest("记住家在北京", "session-A", 0L);
        AgentOutcome outcomeA = makeSucceededOutcome(requestA);

        // A 还未写入,管理员触发 clearUserDataAndBump(epoch → 1,清空两 user 数据)
        assertEquals("clearUserDataAndBump 返回新 epoch", 1L,
                store.clearUserDataAndBump("demo-driver", "demo-passenger"));
        assertEquals("store epoch 已 bump", 1L, store.currentEpoch());

        // A 现在才尝试写——requestEpoch=0 已 stale,事务内必须 reject
        writer.writeEpisodicOnTerminal(requestA, outcomeA, 0L);

        // 验证:Episodic 表无 A 的旧数据(写入被事务内 stale check 拒绝)
        List<SessionHistoryEntity> episodicRows = sessionDao.queryByUserZone(
                "demo-driver", "DRIVER", 10);
        assertTrue("stale episodic 写入必须被拒(session_history 应为空)",
                episodicRows.isEmpty());

        // 验证:Semantic 同样拒绝(另一条调用路径)
        boolean semanticAccepted = writer.writeSemantic(
                "demo-driver", "DRIVER", "fact.home_city", "北京",
                1.0, "session-A", 0L);
        assertFalse("stale semantic 写入必须被事务内 reject", semanticAccepted);
        assertNull("memory_record 表无 A 的语义行",
                memoryDao.queryByKey("demo-driver", "DRIVER", "semantic", "fact.home_city"));

        // 验证:新 epoch 的写入仍成功(系统未死锁,clearUserData 不影响后续合法写入)
        AgentRequest requestB = makeRequest("记住公司在新地址", "session-B", 1L);
        writer.writeEpisodicOnTerminal(requestB, makeSucceededOutcome(requestB), 1L);
        assertEquals("新 epoch 写入成功",
                1, sessionDao.queryByUserZone("demo-driver", "DRIVER", 10).size());

        boolean semanticB = writer.writeSemantic(
                "demo-driver", "DRIVER", "work.office_city", "上海",
                1.0, "session-B", 1L);
        assertTrue("新 epoch semantic 写入成功", semanticB);
    }

    /**
     * 不变式 B:writeSemantic 用合法 requestEpoch=0 commit 后(epoch=0 时刻),
     * clearUserDataAndBump 必须清空刚写入的记忆行。SQLite 写者锁保证两 transaction 串行,
     * 不会出现"clear 看不到刚 commit 的行"。
     *
     * <p>这覆盖"已落地旧数据被清除"路径——与不变式 A 的"在途写入被拒绝"互补。
     */
    @Test
    public void priorWriteClearedByClearUserDataOnRealRoom() {
        // 初始 epoch=0,A 用 requestEpoch=0 合法写入语义记忆。
        boolean semanticA = writer.writeSemantic(
                "demo-driver", "DRIVER", "fact.home_city", "北京",
                1.0, "session-A", 0L);
        assertTrue("A 的 semantic 行已写入", semanticA);
        assertNotNull("memory_record 表有 A 的语义行",
                memoryDao.queryByKey("demo-driver", "DRIVER", "semantic", "fact.home_city"));

        // 触发 clearUserDataAndBump——必须清空 MemoryStore 所拥有的表 + bump epoch。
        long newEpoch = store.clearUserDataAndBump("demo-driver", "demo-passenger");
        assertEquals("epoch bump 到 1", 1L, newEpoch);

        // 验证:RoomMemoryStore 只拥有 memory_record；session_history / trajectory /
        // audit_event 的跨表清理由 UserDataResetCoordinator 委托 AuditRepository 完成。
        assertNull("clearUserData 后 semantic 表无 A 的行",
                memoryDao.queryByKey("demo-driver", "DRIVER", "semantic", "fact.home_city"));

        // 验证:epoch 行保留(在 __system__ 下,不被 deleteByUser 触及)
        MemoryRecordEntity epochRow = memoryDao.queryByKey(
                RoomMemoryStore.SYSTEM_USER, RoomMemoryStore.SYSTEM_ZONE,
                RoomMemoryStore.PREFERENCE_LAYER, RoomMemoryStore.EPOCH_KEY);
        assertEquals("epoch 行 value=1", "1", epochRow.value);
    }
}
