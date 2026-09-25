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

        writer.writeEpisodic(request, EpisodicTestSupport.write(request, outcome, 0L));

        assertEquals("session_history 1 行", 1, sessionDao.store.size());
        SessionHistoryEntity row = sessionDao.store.get(0);
        assertEquals("demo-driver", row.userId);
        assertEquals("driver", row.zone);
        assertEquals("sess-001", row.sessionId);
        assertEquals(1234567890L, row.startedAtMillis);
        assertEquals("DRIVER", row.actor);
        assertEquals("SUCCEEDED", row.finalState);
        assertEquals("DONE", row.stopReason);
        assertEquals(1500L, row.durationMs);
        assertNotNull(row.trajectoryJson);
    }

    @Test
    public void forgedEventKindIsRejectedAtWriterBoundary() {
        FakeSessionHistoryDao sessionDao = new FakeSessionHistoryDao();
        RoomMemoryWriter writer = new RoomMemoryWriter(sessionDao, new FakeMemoryRecordDao(),
                null, syncRunner());
        AgentRequest request = AgentRequest.builder("测试", Actor.DRIVER)
                .sessionId("sess-forged").occupantZone(VehicleZone.DRIVER).build();
        AgentOutcome outcome = new AgentOutcome(request.getRequestId(), TaskState.SUCCEEDED,
                StopReason.DONE, new Trajectory(System.currentTimeMillis()), 10L);
        EpisodicWrite safe = EpisodicTestSupport.write(request, outcome, 0L);
        EpisodicWrite forged = new EpisodicWrite(safe.requestId, safe.userId, safe.zone,
                safe.sessionId, safe.actor, safe.startedAtMillis, safe.finalState,
                safe.stopReason, safe.durationMs, safe.turnCount,
                safe.summaryJson.replace("\"eventKind\":null", "\"eventKind\":\"navigation\""),
                safe.requestEpoch);
        writer.writeEpisodic(request, forged);
        assertTrue(sessionDao.store.isEmpty());
    }

    @Test
    public void forgedOwnerIsRejectedAtWriterBoundary() {
        FakeSessionHistoryDao sessionDao = new FakeSessionHistoryDao();
        RoomMemoryWriter writer = new RoomMemoryWriter(sessionDao, new FakeMemoryRecordDao(),
                null, syncRunner());
        AgentRequest request = AgentRequest.builder("导航到人民广场", Actor.DRIVER)
                .sessionId("owner-bound").occupantZone(VehicleZone.DRIVER).build();
        AgentOutcome outcome = new AgentOutcome(request.getRequestId(), TaskState.SUCCEEDED,
                StopReason.DONE, new Trajectory(System.currentTimeMillis()), 10L);
        EpisodicWrite safe = EpisodicTestSupport.write(request, outcome, request.getEpoch());
        EpisodicWrite forged = new EpisodicWrite(safe.requestId, "demo-passenger", safe.zone,
                safe.sessionId, safe.actor, safe.startedAtMillis, safe.finalState,
                safe.stopReason, safe.durationMs, safe.turnCount, safe.summaryJson,
                safe.requestEpoch);
        writer.writeEpisodic(request, forged);
        assertTrue(sessionDao.store.isEmpty());
    }

    @Test
    public void writeEpisodicUsesFreshDatabaseRead() {
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
        episodic.recallEpisodic(new MemoryScope("demo-driver", VehicleZone.DRIVER), "上次做过什么", 5);
        assertEquals("primed cache: DAO 调用 1 次", 1, sessionDao.queryByUserZoneCallCount);

        // 2) every recall reads the database, so a clear cannot expose a stale cache entry.
        episodic.recallEpisodic(new MemoryScope("demo-driver", VehicleZone.DRIVER), "上次做过什么", 5);
        assertEquals(2, sessionDao.queryByUserZoneCallCount);

        // 3) writeEpisodicOnTerminal → invalidateCache
        Trajectory trajectory = new Trajectory(1234567890L);
        AgentOutcome outcome = new AgentOutcome(
                request.getRequestId(), TaskState.SUCCEEDED, StopReason.DONE, trajectory, 1500L);
        writer.writeEpisodic(request, EpisodicTestSupport.write(request, outcome, 0L));

        // 4) 下一次 recallEpisodic 必须再次调 DAO
        episodic.recallEpisodic(new MemoryScope("demo-driver", VehicleZone.DRIVER), "上次做过什么", 5);
        assertEquals(3, sessionDao.queryByUserZoneCallCount);
    }

    @Test
    public void writeSemanticPersistsSemanticLayerRow() {
        FakeSessionHistoryDao sessionDao = new FakeSessionHistoryDao();
        FakeMemoryRecordDao memoryDao = new FakeMemoryRecordDao();
        RoomMemoryWriter writer = new RoomMemoryWriter(sessionDao, memoryDao, null, syncRunner());

        boolean accepted = writer.writeSemantic(MemoryTestRequests.from("demo-driver", "DRIVER", "sess-001", 0L), "allergy.peanut", "严重过敏", 1.0);

        assertTrue("writeSemantic 返回 true", accepted);
        assertEquals(1, memoryDao.store.size());
        MemoryRecordEntity row = memoryDao.store.get(0);
        assertEquals("demo-driver", row.userId);
        assertEquals("driver", row.zone);
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

        writer.writeSemantic(MemoryTestRequests.from("demo-driver", "DRIVER", "sess-001", 0L), "fact.daughter_name", "小红", 1.0);
        String value = writer.readSemantic(MemoryTestRequests.from("demo-driver", "DRIVER", null, 0L), "fact.daughter_name");

        assertEquals("小红", value);
    }

    @Test
    public void readSemanticReturnsNullWhenAbsent() {
        FakeSessionHistoryDao sessionDao = new FakeSessionHistoryDao();
        FakeMemoryRecordDao memoryDao = new FakeMemoryRecordDao();
        RoomMemoryWriter writer = new RoomMemoryWriter(sessionDao, memoryDao, null, syncRunner());

        String value = writer.readSemantic(MemoryTestRequests.from("demo-driver", "DRIVER", null, 0L), "not.existing");

        assertNull(value);
    }

    @Test
    public void deleteSemanticIsScopedAndEpochChecked() {
        FakeMemoryRecordDao memoryDao = new FakeMemoryRecordDao();
        RoomMemoryWriter writer = new RoomMemoryWriter(new FakeSessionHistoryDao(), memoryDao,
                null, syncRunner());
        assertTrue(writer.writeSemantic(MemoryTestRequests.from("demo-driver", "DRIVER", null, 0L), "allergy.peanut", "花生过敏", 1.0));
        assertTrue(writer.writeSemantic(MemoryTestRequests.from("demo-driver", "PASSENGER", null, 0L), "allergy.peanut", "另一乘员", 1.0));
        assertEquals(MemoryDeleteOutcome.STALE_EPOCH,
                writer.deleteSemanticDetailed(MemoryTestRequests.from("demo-driver", "driver", null, 1L), "allergy.peanut"));
        assertTrue(writer.deleteSemantic(MemoryTestRequests.from("demo-driver", "driver", null, 0L), "allergy.peanut"));
        assertNull(writer.readSemantic(MemoryTestRequests.from("demo-driver", "driver", null, 0L), "allergy.peanut"));
        assertEquals("另一乘员", writer.readSemantic(MemoryTestRequests.from("demo-driver", "passenger", null, 0L), "allergy.peanut"));
        assertEquals(MemoryDeleteOutcome.NOT_FOUND,
                writer.deleteSemanticDetailed(MemoryTestRequests.from("demo-driver", "driver", null, 0L), "allergy.peanut"));
    }

    @Test
    public void noopWriterNeverPersists() {
        MemoryWriter noop = MemoryWriter.NOOP;
        AgentRequest request = AgentRequest.builder("q", Actor.DRIVER).build();
        AgentOutcome outcome = new AgentOutcome(
                request.getRequestId(), TaskState.SUCCEEDED, StopReason.DONE,
                new Trajectory(1L), 1L);

        noop.writeEpisodic(request, EpisodicTestSupport.write(request, outcome, 0L));
        boolean accepted = noop.writeSemantic(MemoryTestRequests.from("u", "z", "s", 0L), "k", "v", 1.0);
        String value = noop.readSemantic(MemoryTestRequests.from("u", "z", null, 0L), "k");

        assertFalse(accepted);
        assertNull(value);
    }

    private static final class FakeSessionHistoryDao implements SessionHistoryDao {
        @Override public int deleteSanitizedLegacyRows() { return 0; }
        @Override public int deleteExact(String userId, String zone, String sessionId, long startedAtMillis) { return 0; }
        @Override public int deleteByUser(String userId) { return 0; }
        @Override public int deleteOlderThan(String userId, String zone, long cutoff) { return 0; }
        @Override public int retainLatest(String userId, String zone, int keep) { return 0; }

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
        @Override public java.util.List<String> queryKeysByUserZoneLayer(String u, String z, String l) {
            return queryByUserZoneLayer(u, z, l).stream().map(row -> row.key).toList();
        }
        @Override public int countByUserZoneLayer(String userId, String zone, String layer) { return queryByUserZoneLayer(userId, zone, layer).size(); }

        @Override public int deleteByKey(String userId, String zone, String layer, String key) {
            int before = store.size();
            store.removeIf(row -> userId.equals(row.userId) && zone.equals(row.zone)
                    && layer.equals(row.layer) && key.equals(row.key));
            return before - store.size();
        }

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
