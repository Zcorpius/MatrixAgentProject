package com.matrix.agent.data.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.task.StopReason;
import com.matrix.agent.task.TaskState;
import com.matrix.agent.task.Trajectory;
import com.matrix.agent.task.identity.Actor;
import com.matrix.agent.task.identity.AgentRequest;
import com.matrix.agent.task.identity.VehicleZone;
import com.matrix.agent.data.db.MemoryRecordDao;
import com.matrix.agent.data.db.MemoryRecordEntity;
import com.matrix.agent.data.db.SessionHistoryDao;
import com.matrix.agent.data.db.SessionHistoryEntity;

/**
 * RoomMemoryWriter epoch 原子性测试。
 *
 * <p>验证用户硬约束——"epoch 检查必须与数据库写操作同事务":
 * <ul>
 *   <li>requestEpoch == currentEpoch(__system__ 行)→ 写入成功;</li>
 *   <li>requestEpoch != currentEpoch → 写入被事务内拒绝(Dao.insert 0 次 / Dao.upsert 0 次);</li>
 *   <li>模拟 clearUserData bump epoch 后,旧 requestEpoch 的写入全被拒;</li>
 *   <li>readEpochFromSystemRow 直查 __system__ 行,不走 RoomMemoryStore AtomicLong 缓存。</li>
 * </ul>
 *
 * <p>测试用 fake DAO + 同步 TransactionRunner(Runnable::run)。生产 path
 * transactionRunner=database::runInTransaction,Room + SQLite 单写者锁保证与
 * clearUserDataAndBump 序列化执行(本 JVM 测试不模拟跨线程,只验证 epoch 比较 + skip 逻辑)。
 */
public final class RoomMemoryWriterEpochGateTest {

    private static RoomMemoryStore.TransactionRunner syncRunner() {
        return Runnable::run;
    }

    /**
     * 写入 __system__ epoch 行(模拟 clearUserDataAndBump 自增后的状态)。
     */
    private static void seedEpoch(FakeMemoryRecordDao dao, long epoch) {
        MemoryRecordEntity row = new MemoryRecordEntity();
        row.userId = RoomMemoryStore.SYSTEM_USER;
        row.zone = RoomMemoryStore.SYSTEM_ZONE;
        row.layer = RoomMemoryStore.PREFERENCE_LAYER;
        row.key = RoomMemoryStore.EPOCH_KEY;
        row.value = Long.toString(epoch);
        row.capturedAtMs = System.currentTimeMillis();
        dao.store.add(row);
    }

    @Test
    public void writeEpisodicSameEpochPersistsRow() {
        FakeSessionHistoryDao sessionDao = new FakeSessionHistoryDao();
        FakeMemoryRecordDao memoryDao = new FakeMemoryRecordDao();
        seedEpoch(memoryDao, 5L);
        RoomMemoryWriter writer = new RoomMemoryWriter(sessionDao, memoryDao, null, syncRunner());

        AgentRequest request = AgentRequest.builder("q", Actor.DRIVER)
                .sessionId("sess-1").epoch(5L).build();
        AgentOutcome outcome = new AgentOutcome(request.getRequestId(),
                TaskState.SUCCEEDED, StopReason.DONE, new Trajectory(1L), 1L);

        writer.writeEpisodicOnTerminal(request, outcome, 5L);

        assertEquals("同 epoch → session_history 1 行", 1, sessionDao.store.size());
    }

    @Test
    public void writeEpisodicDifferentEpochRejectsWrite() {
        FakeSessionHistoryDao sessionDao = new FakeSessionHistoryDao();
        FakeMemoryRecordDao memoryDao = new FakeMemoryRecordDao();
        seedEpoch(memoryDao, 6L);  // clearUserData 已 bump 到 6
        RoomMemoryWriter writer = new RoomMemoryWriter(sessionDao, memoryDao, null, syncRunner());

        AgentRequest request = AgentRequest.builder("q", Actor.DRIVER)
                .sessionId("sess-1").epoch(5L).build();  // 任务在 epoch=5 时启动
        AgentOutcome outcome = new AgentOutcome(request.getRequestId(),
                TaskState.SUCCEEDED, StopReason.DONE, new Trajectory(1L), 1L);

        writer.writeEpisodicOnTerminal(request, outcome, 5L);

        assertEquals("不同 epoch → session_history 0 行(事务内拒绝)",
                0, sessionDao.store.size());
    }

    @Test
    public void writeSemanticSameEpochPersistsRow() {
        FakeSessionHistoryDao sessionDao = new FakeSessionHistoryDao();
        FakeMemoryRecordDao memoryDao = new FakeMemoryRecordDao();
        seedEpoch(memoryDao, 3L);
        RoomMemoryWriter writer = new RoomMemoryWriter(sessionDao, memoryDao, null, syncRunner());

        boolean accepted = writer.writeSemantic(
                "demo-driver", "DRIVER", "allergy.peanut", "严重", 1.0, "sess-1", 3L);

        assertTrue("同 epoch → writeSemantic 返回 true", accepted);
        // memoryDao 含 1 行 __epoch__ + 1 行 semantic = 2 行
        assertEquals("memory_record 含 __epoch__ + semantic 共 2 行", 2, memoryDao.store.size());
        MemoryRecordEntity semanticRow = memoryDao.queryByKey(
                "demo-driver", "DRIVER", "semantic", "allergy.peanut");
        assertTrue("semantic 行存在", semanticRow != null);
        assertEquals("严重", semanticRow.value);
    }

    @Test
    public void writeSemanticDifferentEpochRejectsWrite() {
        FakeSessionHistoryDao sessionDao = new FakeSessionHistoryDao();
        FakeMemoryRecordDao memoryDao = new FakeMemoryRecordDao();
        seedEpoch(memoryDao, 4L);
        RoomMemoryWriter writer = new RoomMemoryWriter(sessionDao, memoryDao, null, syncRunner());

        boolean accepted = writer.writeSemantic(
                "demo-driver", "DRIVER", "allergy.peanut", "严重", 1.0, "sess-1", 3L);

        assertFalse("不同 epoch → writeSemantic 返回 false", accepted);
        assertEquals("memory_record 不应新增(stale 写入被拒)",
                1, memoryDao.store.size());  // 仅 __epoch__ 行,无 semantic 行
    }

    @Test
    public void clearUserDataBumpRejectsAllSubsequentWrites() {
        // 模拟场景:任务 A 启动时 epoch=2 → 跑期间用户 clearUserData bump 到 3
        // → 任务 A 完成时 writeEpisodicOnTerminal(epoch=2) 必须被拒
        FakeSessionHistoryDao sessionDao = new FakeSessionHistoryDao();
        FakeMemoryRecordDao memoryDao = new FakeMemoryRecordDao();
        seedEpoch(memoryDao, 3L);  // clearUserData 后 epoch=3
        RoomMemoryWriter writer = new RoomMemoryWriter(sessionDao, memoryDao, null, syncRunner());

        AgentRequest requestA = AgentRequest.builder("q", Actor.DRIVER)
                .sessionId("a").epoch(2L).build();  // 任务启动时 capturedEpoch=2
        AgentOutcome outcomeA = new AgentOutcome(requestA.getRequestId(),
                TaskState.SUCCEEDED, StopReason.DONE, new Trajectory(1L), 1L);

        writer.writeEpisodicOnTerminal(requestA, outcomeA, 2L);
        boolean semanticAccepted = writer.writeSemantic(
                "demo-driver", "DRIVER", "fact.x", "y", 1.0, "a", 2L);

        assertEquals("episodic 写入被拒", 0, sessionDao.store.size());
        assertFalse("semantic 写入被拒", semanticAccepted);
    }

    private static final class FakeSessionHistoryDao implements SessionHistoryDao {
        final List<SessionHistoryEntity> store = new ArrayList<>();
        @Override public void insert(SessionHistoryEntity entity) { store.add(entity); }
        @Override public List<SessionHistoryEntity> queryByUserZone(String u, String z, int l) {
            return Collections.emptyList();
        }
        @Override public List<SessionHistoryEntity> queryBySession(String s) {
            return Collections.emptyList();
        }
        @Override public int deleteByUserZone(String u, String z) { return 0; }
    }

    private static final class FakeMemoryRecordDao implements MemoryRecordDao {
        final List<MemoryRecordEntity> store = new ArrayList<>();
        @Override public void upsert(MemoryRecordEntity entity) {
            // REPLACE 语义:同主键先删后插(简化实现)
            for (int i = 0; i < store.size(); i++) {
                MemoryRecordEntity e = store.get(i);
                if (e.userId.equals(entity.userId) && e.zone.equals(entity.zone)
                        && e.layer.equals(entity.layer) && e.key.equals(entity.key)) {
                    store.set(i, entity);
                    return;
                }
            }
            store.add(entity);
        }
        @Override public List<MemoryRecordEntity> queryByUserZoneLayer(String u, String z, String l) {
            return Collections.emptyList();
        }
        @Override public MemoryRecordEntity queryByKey(String userId, String zone, String layer, String key) {
            for (MemoryRecordEntity e : store) {
                if (userId.equals(e.userId) && zone.equals(e.zone)
                        && layer.equals(e.layer) && key.equals(e.key)) {
                    return e;
                }
            }
            return null;
        }
        @Override public int deleteByUser(String u) { return 0; }
        @Override public int deleteByUserZone(String u, String z) { return 0; }
    }
}
