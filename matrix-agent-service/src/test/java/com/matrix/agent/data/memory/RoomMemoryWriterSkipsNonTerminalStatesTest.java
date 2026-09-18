package com.matrix.agent.data.memory;

import com.matrix.agent.task.persistence.EpisodicSummary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.json.JSONObject;
import org.junit.Test;

import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.task.StopReason;
import com.matrix.agent.task.TaskState;
import com.matrix.agent.task.Trajectory;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.identity.VehicleZone;
import com.matrix.agent.data.db.MemoryRecordDao;
import com.matrix.agent.data.db.MemoryRecordEntity;
import com.matrix.agent.data.db.SessionHistoryDao;
import com.matrix.agent.data.db.SessionHistoryEntity;

/**
 * RoomMemoryWriter 终态过滤——非 SUCCEEDED / FAILED / PARTIALLY_SUCCEEDED
 * 直接 skip,session_history 不新增行。SUCCEEDED 写入 + trajectoryJson 是 summary JSON
 * (不含 "iterations" 字段,确认与 TrajectoryCodec 区分)。
 */
public final class RoomMemoryWriterSkipsNonTerminalStatesTest {

    private static RoomMemoryStore.TransactionRunner syncRunner() {
        return Runnable::run;
    }

    @Test
    public void cancelledOutcomeSkipsInsert() {
        FakeSessionHistoryDao sessionDao = new FakeSessionHistoryDao();
        FakeMemoryRecordDao memoryDao = new FakeMemoryRecordDao();
        RoomMemoryWriter writer = new RoomMemoryWriter(sessionDao, memoryDao, null, syncRunner());

        AgentRequest request = AgentRequest.builder("q", Actor.DRIVER).epoch(0L).build();
        AgentOutcome outcome = new AgentOutcome(request.getRequestId(),
                TaskState.CANCELLED, StopReason.CANCELLED, new Trajectory(1L), 1L);

        writer.writeEpisodic(EpisodicTestSupport.write(request, outcome, 0L));

        assertEquals("CANCELLED → session_history 0 行", 0, sessionDao.store.size());
    }

    @Test
    public void timedOutOutcomeSkipsInsert() {
        FakeSessionHistoryDao sessionDao = new FakeSessionHistoryDao();
        FakeMemoryRecordDao memoryDao = new FakeMemoryRecordDao();
        RoomMemoryWriter writer = new RoomMemoryWriter(sessionDao, memoryDao, null, syncRunner());

        AgentRequest request = AgentRequest.builder("q", Actor.DRIVER).epoch(0L).build();
        AgentOutcome outcome = new AgentOutcome(request.getRequestId(),
                TaskState.TIMED_OUT, StopReason.TIMEOUT, new Trajectory(1L), 1L);

        writer.writeEpisodic(EpisodicTestSupport.write(request, outcome, 0L));

        assertEquals("TIMED_OUT → session_history 0 行", 0, sessionDao.store.size());
    }

    @Test
    public void succeededOutcomeWritesSummaryWithoutIterationsField() throws Exception {
        FakeSessionHistoryDao sessionDao = new FakeSessionHistoryDao();
        FakeMemoryRecordDao memoryDao = new FakeMemoryRecordDao();
        RoomMemoryWriter writer = new RoomMemoryWriter(sessionDao, memoryDao, null, syncRunner());

        AgentRequest request = AgentRequest.builder("把温度调到 24 度", Actor.DRIVER)
                .sessionId("sess-1")
                .occupantZone(VehicleZone.DRIVER)
                .epoch(0L).build();
        AgentOutcome outcome = new AgentOutcome(request.getRequestId(),
                TaskState.SUCCEEDED, StopReason.DONE, new Trajectory(1L), 1L);

        writer.writeEpisodic(EpisodicTestSupport.write(request, outcome, 0L));

        assertEquals("SUCCEEDED → session_history 1 行", 1, sessionDao.store.size());
        SessionHistoryEntity row = sessionDao.store.get(0);
        String json = row.trajectoryJson;
        // 核心断言:summary JSON **不含 "iterations" 字段**(与 TrajectoryCodec 区分)
        assertFalse("summary 不含 iterations: " + json, json.contains("iterations"));
        assertFalse("summary 不含 arguments: " + json, json.contains("arguments"));
        assertFalse("summary 不含 assistant: " + json, json.contains("\"assistant\""));

        // 但含 EpisodicSummary 的固定字段
        JSONObject parsed = new JSONObject(json);
        assertEquals("SUCCEEDED", parsed.getString("finalState"));
        assertTrue(parsed.has("startedAtMillis"));
        assertTrue(parsed.has("durationMs"));
        assertTrue(parsed.has("turnCount"));
        assertTrue(parsed.has("successfulCapabilities"));
    }

    private static void assertTrue(boolean condition) {
        org.junit.Assert.assertTrue(condition);
    }

    @Test
    public void failedOutcomeWritesSummary() {
        FakeSessionHistoryDao sessionDao = new FakeSessionHistoryDao();
        FakeMemoryRecordDao memoryDao = new FakeMemoryRecordDao();
        RoomMemoryWriter writer = new RoomMemoryWriter(sessionDao, memoryDao, null, syncRunner());

        AgentRequest request = AgentRequest.builder("失败任务", Actor.DRIVER).epoch(0L).build();
        AgentOutcome outcome = new AgentOutcome(request.getRequestId(),
                TaskState.FAILED, StopReason.POLICY_HALT, new Trajectory(1L), 1L);

        writer.writeEpisodic(EpisodicTestSupport.write(request, outcome, 0L));

        assertEquals("FAILED → session_history 1 行", 1, sessionDao.store.size());
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
        @Override public void upsert(MemoryRecordEntity entity) { store.add(entity); }
        @Override public List<MemoryRecordEntity> queryByUserZoneLayer(String u, String z, String l) {
            return Collections.emptyList();
        }
        @Override public MemoryRecordEntity queryByKey(String userId, String zone, String layer, String key) {
            return null;
        }
        @Override public int deleteByUser(String u) { return 0; }
        @Override public int deleteByUserZone(String u, String z) { return 0; }
    }
}
