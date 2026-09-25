package com.matrix.agent.data.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
 * RoomMemoryWriter fail-log 测试——Dao throw 时不向上传播,
 * writeSemantic 返回 false,readSemantic 返回 null。
 *
 * <p>语义与 auditRepository fail-open 一致——Memory 写入失败仅 Log.w + 计数,不影响主路径。
 *
 * <p>接口加 requestEpoch 参数。TransactionRunner 用 Runnable::run。
 */
public final class RoomMemoryWriterFailLogTest {

    private static RoomMemoryStore.TransactionRunner syncRunner() {
        return Runnable::run;
    }

    @Test
    public void writeEpisodicSwallowsDaoException() {
        ThrowingSessionDao sessionDao = new ThrowingSessionDao();
        ThrowingMemoryDao memoryDao = new ThrowingMemoryDao();
        RoomMemoryWriter writer = new RoomMemoryWriter(sessionDao, memoryDao, null, syncRunner());

        AgentRequest request = AgentRequest.builder("q", Actor.DRIVER)
                .sessionId("sess-001").build();
        AgentOutcome outcome = new AgentOutcome(
                request.getRequestId(), TaskState.SUCCEEDED, StopReason.DONE,
                new Trajectory(1L), 1L);

        // 不抛 —— fail-log
        writer.writeEpisodic(request, EpisodicTestSupport.write(request, outcome, 0L));
        // 还要再调一次,确认多次失败都吞掉
        writer.writeEpisodic(request, EpisodicTestSupport.write(request, outcome, 0L));
        assertTrue("多次 fail-log 都吞掉", true);
    }

    @Test
    public void writeSemanticReturnsFalseOnDaoException() {
        ThrowingSessionDao sessionDao = new ThrowingSessionDao();
        ThrowingMemoryDao memoryDao = new ThrowingMemoryDao();
        RoomMemoryWriter writer = new RoomMemoryWriter(sessionDao, memoryDao, null, syncRunner());

        boolean accepted = writer.writeSemantic(MemoryTestRequests.from("demo-driver", "DRIVER", "sess-001", 0L), "allergy.peanut", "严重", 1.0);

        assertFalse("Dao throw → writeSemantic 返回 false", accepted);
    }

    @Test
    public void readSemanticReturnsNullOnDaoException() {
        ThrowingSessionDao sessionDao = new ThrowingSessionDao();
        ThrowingMemoryDao memoryDao = new ThrowingMemoryDao();
        RoomMemoryWriter writer = new RoomMemoryWriter(sessionDao, memoryDao, null, syncRunner());

        String value = writer.readSemantic(MemoryTestRequests.from("demo-driver", "DRIVER", null, 0L), "any.key");

        assertNull("Dao throw → readSemantic 返回 null", value);
    }

    @Test
    public void nullArgsAreSafeNoOp() {
        ThrowingSessionDao sessionDao = new ThrowingSessionDao();
        ThrowingMemoryDao memoryDao = new ThrowingMemoryDao();
        RoomMemoryWriter writer = new RoomMemoryWriter(sessionDao, memoryDao, null, syncRunner());

        // null 参数不抛 —— 直接 return / return false / return null
        writer.writeEpisodic(null, EpisodicTestSupport.write(null, null, 0L));
        assertFalse(writer.writeSemantic(MemoryTestRequests.from(null, null, "s", 0L), null, "v", 1.0));
        assertNull(writer.readSemantic(MemoryTestRequests.from(null, null, null, 0L), null));
    }

    @Test
    public void episodicWithNullEpisodicSourceIsSafe() {
        // RoomMemoryWriter(sessionDao, memoryDao, null, runner) —— episodicSource 可空
        FakeSessionDao sessionDao = new FakeSessionDao();
        FakeMemoryDao memoryDao = new FakeMemoryDao();
        RoomMemoryWriter writer = new RoomMemoryWriter(sessionDao, memoryDao, null, syncRunner());

        AgentRequest request = AgentRequest.builder("q", Actor.DRIVER).sessionId("s1").build();
        AgentOutcome outcome = new AgentOutcome(
                request.getRequestId(), TaskState.SUCCEEDED, StopReason.DONE,
                new Trajectory(1L), 1L);

        writer.writeEpisodic(request, EpisodicTestSupport.write(request, outcome, 0L));
        assertEquals("Dao insert 1 行(episodicSource=null 不影响)", 1, sessionDao.store.size());
    }

    private static final class ThrowingSessionDao implements SessionHistoryDao {
        @Override public int deleteSanitizedLegacyRows() { return 0; }
        @Override public int deleteExact(String userId, String zone, String sessionId, long startedAtMillis) { return 0; }
        @Override public int deleteByUser(String userId) { return 0; }
        @Override public int deleteOlderThan(String userId, String zone, long cutoff) { return 0; }
        @Override public int retainLatest(String userId, String zone, int keep) { return 0; }

        @Override public void insert(SessionHistoryEntity entity) {
            throw new RuntimeException("simulated SQL failure on insert");
        }
        @Override public List<SessionHistoryEntity> queryByUserZone(String u, String z, int l) {
            throw new RuntimeException("simulated SQL failure on query");
        }
        @Override public List<SessionHistoryEntity> queryBySession(String s) {
            throw new RuntimeException("simulated SQL failure");
        }
        @Override public int deleteByUserZone(String u, String z) {
            throw new RuntimeException("simulated SQL failure");
        }
    }

    private static final class ThrowingMemoryDao implements MemoryRecordDao {
        @Override public java.util.List<String> queryKeysByUserZoneLayer(String u, String z, String l) {
            return queryByUserZoneLayer(u, z, l).stream().map(row -> row.key).toList();
        }
        @Override public int countByUserZoneLayer(String userId, String zone, String layer) { return queryByUserZoneLayer(userId, zone, layer).size(); }

        @Override public int deleteByKey(String userId, String zone, String layer, String key) { return 0; }

        @Override public void upsert(MemoryRecordEntity entity) {
            throw new RuntimeException("simulated SQL failure on upsert");
        }
        @Override public List<MemoryRecordEntity> queryByUserZoneLayer(String u, String z, String l) {
            throw new RuntimeException("simulated SQL failure");
        }
        @Override public MemoryRecordEntity queryByKey(String u, String z, String l, String k) {
            throw new RuntimeException("simulated SQL failure");
        }
        @Override public int deleteByUser(String u) { throw new RuntimeException(); }
        @Override public int deleteByUserZone(String u, String z) { throw new RuntimeException(); }
    }

    private static final class FakeSessionDao implements SessionHistoryDao {
        @Override public int deleteSanitizedLegacyRows() { return 0; }
        @Override public int deleteExact(String userId, String zone, String sessionId, long startedAtMillis) { return 0; }
        @Override public int deleteByUser(String userId) { return 0; }
        @Override public int deleteOlderThan(String userId, String zone, long cutoff) { return 0; }
        @Override public int retainLatest(String userId, String zone, int keep) { return 0; }

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

    private static final class FakeMemoryDao implements MemoryRecordDao {
        @Override public java.util.List<String> queryKeysByUserZoneLayer(String u, String z, String l) {
            return queryByUserZoneLayer(u, z, l).stream().map(row -> row.key).toList();
        }
        @Override public int countByUserZoneLayer(String userId, String zone, String layer) { return queryByUserZoneLayer(userId, zone, layer).size(); }

        @Override public int deleteByKey(String userId, String zone, String layer, String key) { return 0; }

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
