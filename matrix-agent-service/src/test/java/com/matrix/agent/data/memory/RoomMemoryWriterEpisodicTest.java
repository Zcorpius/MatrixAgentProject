package com.matrix.agent.data.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.data.memory.MemoryWriter;
import com.matrix.agent.task.StopReason;
import com.matrix.agent.task.TaskState;
import com.matrix.agent.task.Trajectory;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.identity.VehicleZone;
import com.matrix.agent.data.memory.MemoryLayer;
import com.matrix.agent.data.memory.MemoryScope;
import com.matrix.agent.data.db.MemoryRecordDao;
import com.matrix.agent.data.db.MemoryRecordEntity;
import com.matrix.agent.data.db.SessionHistoryDao;
import com.matrix.agent.data.db.SessionHistoryEntity;

/**
 * RoomMemoryWriter JVM 测试——验证 episodic 自动写入路径 +
 * semantic save/get 闭环 + invalidateCache 触发。
 *
 * <p>接口加 requestEpoch 参数。RoomMemoryWriter 第 4 参是
 * {@link RoomMemoryStore.TransactionRunner},测试传 {@code Runnable::run}
 * (同步执行,生产传 {@code database::runInTransaction})。默认 __system__ epoch 行不存在
 * → readEpochFromSystemRow 返回 0 → requestEpoch=0 → 写入成功。
 *
 * <p>真实 Room _Impl.java SQL 验证由 androidTest 负责(MemorySemanticSaveIntegrationTest)。
 */
public final class RoomMemoryWriterEpisodicTest {

    private static RoomMemoryStore.TransactionRunner syncRunner() {
        return Runnable::run;
    }

    @Test
    public void writeEpisodicOnTerminalPersistsSessionHistoryRow() {
        FakeSessionHistoryDao sessionDao = new FakeSessionHistoryDao();
        FakeMemoryRecordDao memoryDao = new FakeMemoryRecordDao();
        EpisodicMemorySourceImpl episodic = new EpisodicMemorySourceImpl(sessionDao, 5);
        RoomMemoryWriter writer = new RoomMemoryWriter(sessionDao, memoryDao, episodic, syncRunner());

        AgentRequest request = AgentRequest.builder("把温度调到 24 度", Actor.DRIVER)
                .sessionId("sess-001")
                .occupantZone(VehicleZone.DRIVER)
                .epoch(0L)
                .build();
        Trajectory trajectory = new Trajectory(1234567890L);
        AgentOutcome outcome = new AgentOutcome(
                request.getRequestId(), TaskState.SUCCEEDED, StopReason.DONE, trajectory, 1500L);

        writer.writeEpisodic(EpisodicTestSupport.write(request, outcome, 0L));

        assertEquals("session_history 1 行", 1, sessionDao.store.size());
        SessionHistoryEntity row = sessionDao.store.get(0);
        assertEquals("demo-driver", row.userId);
        assertEquals("DRIVER", row.zone);
        assertEquals("sess-001", row.sessionId);
        assertEquals(1234567890L, row.startedAtMillis);
        assertEquals("DRIVER", row.actor);
        assertEquals("SUCCEEDED", row.finalState);
        assertEquals("DONE", row.stopReason);
        assertEquals(1500L, row.durationMs);
        assertNotNull(row.trajectoryJson);
    }

    @Test
    public void writeEpisodicInvalidatesEpisodicCache() {
        FakeSessionHistoryDao sessionDao = new FakeSessionHistoryDao();
        FakeMemoryRecordDao memoryDao = new FakeMemoryRecordDao();
        EpisodicMemorySourceImpl episodic = new EpisodicMemorySourceImpl(sessionDao, 5);
        RoomMemoryWriter writer = new RoomMemoryWriter(sessionDao, memoryDao, episodic, syncRunner());

        AgentRequest request = AgentRequest.builder("把温度调到 24 度", Actor.DRIVER)
                .sessionId("sess-001")
                .occupantZone(VehicleZone.DRIVER)
                .epoch(0L)
                .build();
        // 1) populate cache:recallEpisodic 1 次 → DAO callCount=1
        episodic.recallEpisodic(new MemoryScope("demo-driver", VehicleZone.DRIVER), "q", 5);
        assertEquals("primed cache: DAO 调用 1 次", 1, sessionDao.queryByUserZoneCallCount);

        // 2) cache hit:再 recallEpisodic → DAO callCount 仍 1
        episodic.recallEpisodic(new MemoryScope("demo-driver", VehicleZone.DRIVER), "q", 5);
        assertEquals("cache hit: DAO 仍只调 1 次", 1, sessionDao.queryByUserZoneCallCount);

        // 3) writeEpisodicOnTerminal → invalidateCache
        Trajectory trajectory = new Trajectory(1234567890L);
        AgentOutcome outcome = new AgentOutcome(
                request.getRequestId(), TaskState.SUCCEEDED, StopReason.DONE, trajectory, 1500L);
        writer.writeEpisodic(EpisodicTestSupport.write(request, outcome, 0L));

        // 4) 下一次 recallEpisodic 必须再次调 DAO
        episodic.recallEpisodic(new MemoryScope("demo-driver", VehicleZone.DRIVER), "q", 5);
        assertEquals("invalidate 后 DAO 再次调用", 2, sessionDao.queryByUserZoneCallCount);
    }

    @Test
    public void writeSemanticPersistsSemanticLayerRow() {
        FakeSessionHistoryDao sessionDao = new FakeSessionHistoryDao();
        FakeMemoryRecordDao memoryDao = new FakeMemoryRecordDao();
        RoomMemoryWriter writer = new RoomMemoryWriter(sessionDao, memoryDao, null, syncRunner());

        boolean accepted = writer.writeSemantic(
                "demo-driver", "DRIVER", "allergy.peanut", "严重过敏", 1.0, "sess-001", 0L);

        assertTrue("writeSemantic 返回 true", accepted);
        assertEquals(1, memoryDao.store.size());
        MemoryRecordEntity row = memoryDao.store.get(0);
        assertEquals("demo-driver", row.userId);
        assertEquals("DRIVER", row.zone);
        assertEquals(MemoryLayer.SEMANTIC.wireValue(), row.layer);
        assertEquals("allergy.peanut", row.key);
        assertEquals("严重过敏", row.value);
        assertEquals(1.0, row.score, 0.001);
        assertEquals("sess-001", row.sourceSessionId);
        assertTrue(row.capturedAtMs > 0);
    }

    @Test
    public void readSemanticReturnsUpsertedValue() {
        FakeSessionHistoryDao sessionDao = new FakeSessionHistoryDao();
        FakeMemoryRecordDao memoryDao = new FakeMemoryRecordDao();
        RoomMemoryWriter writer = new RoomMemoryWriter(sessionDao, memoryDao, null, syncRunner());

        writer.writeSemantic("demo-driver", "DRIVER", "fact.daughter_name", "小红", 1.0, "sess-001", 0L);
        String value = writer.readSemantic("demo-driver", "DRIVER", "fact.daughter_name");

        assertEquals("小红", value);
    }

    @Test
    public void readSemanticReturnsNullWhenAbsent() {
        FakeSessionHistoryDao sessionDao = new FakeSessionHistoryDao();
        FakeMemoryRecordDao memoryDao = new FakeMemoryRecordDao();
        RoomMemoryWriter writer = new RoomMemoryWriter(sessionDao, memoryDao, null, syncRunner());

        String value = writer.readSemantic("demo-driver", "DRIVER", "not.existing");

        assertNull(value);
    }

    @Test
    public void noopWriterNeverPersists() {
        MemoryWriter noop = MemoryWriter.NOOP;
        AgentRequest request = AgentRequest.builder("q", Actor.DRIVER).build();
        AgentOutcome outcome = new AgentOutcome(
                request.getRequestId(), TaskState.SUCCEEDED, StopReason.DONE,
                new Trajectory(1L), 1L);

        noop.writeEpisodic(EpisodicTestSupport.write(request, outcome, 0L));
        boolean accepted = noop.writeSemantic("u", "z", "k", "v", 1.0, "s", 0L);
        String value = noop.readSemantic("u", "z", "k");

        assertFalse(accepted);
        assertNull(value);
    }

    private static final class FakeSessionHistoryDao implements SessionHistoryDao {
        final List<SessionHistoryEntity> store = new ArrayList<>();
        int queryByUserZoneCallCount = 0;

        @Override public void insert(SessionHistoryEntity entity) { store.add(entity); }
        @Override public List<SessionHistoryEntity> queryByUserZone(String userId, String zone, int limit) {
            queryByUserZoneCallCount++;
            List<SessionHistoryEntity> result = new ArrayList<>();
            for (SessionHistoryEntity e : store) {
                if (userId.equals(e.userId) && zone.equals(e.zone)) {
                    result.add(e);
                }
            }
            Collections.sort(result, (a, b) -> Long.compare(b.startedAtMillis, a.startedAtMillis));
            return result.size() > limit ? new ArrayList<>(result.subList(0, limit)) : result;
        }
        @Override public List<SessionHistoryEntity> queryBySession(String sessionId) {
            return new ArrayList<>();
        }
        @Override public int deleteByUserZone(String userId, String zone) { return 0; }
    }

    private static final class FakeMemoryRecordDao implements MemoryRecordDao {
        final List<MemoryRecordEntity> store = new ArrayList<>();
        @Override public void upsert(MemoryRecordEntity entity) { store.add(entity); }
        @Override public List<MemoryRecordEntity> queryByUserZoneLayer(String userId, String zone, String layer) {
            return new ArrayList<>();
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
        @Override public int deleteByUser(String userId) { return 0; }
        @Override public int deleteByUserZone(String userId, String zone) { return 0; }
    }
}
