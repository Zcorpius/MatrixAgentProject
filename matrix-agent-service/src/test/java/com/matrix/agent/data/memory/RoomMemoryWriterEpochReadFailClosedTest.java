package com.matrix.agent.data.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.task.StopReason;
import com.matrix.agent.task.TaskState;
import com.matrix.agent.task.Trajectory;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.data.db.MemoryRecordDao;
import com.matrix.agent.data.db.MemoryRecordEntity;
import com.matrix.agent.data.db.SessionHistoryDao;
import com.matrix.agent.data.db.SessionHistoryEntity;

/**
 * RoomMemoryWriter epoch 查询失败 fail-closed 测试。
 *
 * <p>用户硬约束:"系统 epoch 行不存在与查询失败不能用同一个 0L 表示... DAO 异常、
 * 格式错误、事务异常必须直接拒绝写入。写入路径遵循 fail-closed"。
 *
 * <p>老代码 {@code readEpochFromSystemRow} catch 所有异常返回 0L——把"读取失败"
 * 伪装成"epoch=0"。若旧任务 requestEpoch=0 + clearUserData 后真实 epoch 已 bump +
 * 此刻 epoch 行 SELECT 临时抛(SQLite locked / disk I/O / parse error)→ currentEpoch=0
 * == requestEpoch=0 → 事务通过 → 陈旧写入被持久化。
 *
 * <p>改 fail-closed:
 * <ul>
 *   <li>row 存在 + value 合法 long → 返回包装值</li>
 *   <li>row 不存在 / value null → 返回 0L(合法初始 epoch,等价旧版)</li>
 *   <li>DAO 异常 / parse 失败 / dao==null → null(事务内 return,拒绝写入)</li>
 * </ul>
 *
 * <p>覆盖 3 个关键 case:
 * <ol>
 *   <li>throwing memoryDao(queryByKey 抛) → writeEpisodicOnTerminal 不抛 + insert 0 次</li>
 *   <li>throwing memoryDao → writeSemantic 返回 false + upsert 0 次</li>
 *   <li>row 缺失(不 seedEpoch) + requestEpoch=0 → 仍允许写入(row null → 0L 合法初始,与 fail-closed 区分)</li>
 * </ol>
 */
public final class RoomMemoryWriterEpochReadFailClosedTest {

    private static RoomMemoryStore.TransactionRunner syncRunner() {
        return Runnable::run;
    }

    @Test
    public void throwingMemoryDaoRejectsEpisodicWriteAndSwallows() {
        CountingSessionDao sessionDao = new CountingSessionDao();
        ThrowingMemoryDao memoryDao = new ThrowingMemoryDao();
        RoomMemoryWriter writer = new RoomMemoryWriter(sessionDao, memoryDao, null, syncRunner());

        AgentRequest request = AgentRequest.builder("q", Actor.DRIVER)
                .sessionId("s1").epoch(0L).build();
        AgentOutcome outcome = new AgentOutcome(request.getRequestId(),
                TaskState.SUCCEEDED, StopReason.DONE, new Trajectory(1L), 1L);

        // 不抛 —— fail-log;不写 —— fail-closed
        writer.writeEpisodic(EpisodicTestSupport.write(request, outcome, 0L));

        assertEquals("throwing memoryDao → sessionDao.insert 必须 0 次(readEpoch 失败 → 事务内 return)",
                0, sessionDao.insertCount.get());
    }

    @Test
    public void throwingMemoryDaoRejectsSemanticWriteReturnsFalse() {
        CountingSessionDao sessionDao = new CountingSessionDao();
        ThrowingMemoryDao memoryDao = new ThrowingMemoryDao();
        RoomMemoryWriter writer = new RoomMemoryWriter(sessionDao, memoryDao, null, syncRunner());

        boolean accepted = writer.writeSemantic(
                "demo-driver", "DRIVER", "allergy.peanut", "严重", 1.0, "s1", 0L);

        assertFalse("throwing memoryDao → writeSemantic 返回 false(fail-closed)", accepted);
        assertEquals("memoryDao.upsert 必须 0 次(readEpoch 失败 → 事务内 return)",
                0, memoryDao.upsertCount.get());
    }

    @Test
    public void missingEpochRowWithZeroRequestEpochStillWrites() {
        // row 缺失(不 seedEpoch) → readEpochFromSystemRow 返回 0L(合法初始,与 fail-closed 区分)
        // requestEpoch=0 + currentEpoch=0 → 写入允许,与旧版行为等价
        CountingSessionDao sessionDao = new CountingSessionDao();
        FakeEmptyMemoryDao memoryDao = new FakeEmptyMemoryDao();  // store 空,queryByKey 返回 null
        RoomMemoryWriter writer = new RoomMemoryWriter(sessionDao, memoryDao, null, syncRunner());

        AgentRequest request = AgentRequest.builder("q", Actor.DRIVER)
                .sessionId("s2").epoch(0L).build();
        AgentOutcome outcome = new AgentOutcome(request.getRequestId(),
                TaskState.SUCCEEDED, StopReason.DONE, new Trajectory(1L), 1L);

        writer.writeEpisodic(EpisodicTestSupport.write(request, outcome, 0L));

        assertEquals("row 缺失 + requestEpoch=0 → 合法初始,sessionDao.insert 1 次",
                1, sessionDao.insertCount.get());
        assertEquals("row 缺失 → 1 行 episodic 写入", 1, sessionDao.store.size());
    }

    private static final class CountingSessionDao implements SessionHistoryDao {
        final AtomicInteger insertCount = new AtomicInteger();
        final List<SessionHistoryEntity> store = new ArrayList<>();
        @Override public void insert(SessionHistoryEntity entity) {
            insertCount.incrementAndGet();
            store.add(entity);
        }
        @Override public List<SessionHistoryEntity> queryByUserZone(String u, String z, int l) {
            return Collections.emptyList();
        }
        @Override public List<SessionHistoryEntity> queryBySession(String s) {
            return Collections.emptyList();
        }
        @Override public int deleteByUserZone(String u, String z) { return 0; }
    }

    /**
     * 模拟 SQLite disk I/O / locked / parse error:queryByKey 抛,upsert 若被调到则计数 + 抛
     * (readEpoch 失败时 upsert 0 次,断言会暴露"路径错误")。
     */
    private static final class ThrowingMemoryDao implements MemoryRecordDao {
        final AtomicInteger upsertCount = new AtomicInteger();
        @Override public void upsert(MemoryRecordEntity entity) {
            upsertCount.incrementAndGet();
            throw new RuntimeException("upsert should not be reached when readEpoch fails");
        }
        @Override public List<MemoryRecordEntity> queryByUserZoneLayer(String u, String z, String l) {
            throw new RuntimeException("simulated SQL failure");
        }
        @Override public MemoryRecordEntity queryByKey(String u, String z, String l, String k) {
            throw new RuntimeException("simulated SQLite disk I/O error on epoch row SELECT");
        }
        @Override public int deleteByUser(String u) { throw new RuntimeException(); }
        @Override public int deleteByUserZone(String u, String z) { throw new RuntimeException(); }
    }

    /** 空 store 的 Fake DAO——queryByKey 永远返回 null(row 缺失 → 0L 合法初始路径)。 */
    private static final class FakeEmptyMemoryDao implements MemoryRecordDao {
        @Override public void upsert(MemoryRecordEntity entity) { }
        @Override public List<MemoryRecordEntity> queryByUserZoneLayer(String u, String z, String l) {
            return Collections.emptyList();
        }
        @Override public MemoryRecordEntity queryByKey(String u, String z, String l, String k) {
            return null;
        }
        @Override public int deleteByUser(String u) { return 0; }
        @Override public int deleteByUserZone(String u, String z) { return 0; }
    }
}
