package com.matrix.agent.data.audit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.contract.AgentMessage;
import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.task.StopReason;
import com.matrix.agent.task.TaskState;
import com.matrix.agent.task.Trajectory;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.identity.VehicleZone;
import com.matrix.agent.task.persistence.AuditOutcomeEntryFactory;
import com.matrix.agent.data.db.TrajectoryEntity;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RoomAuditRepository 契约测试——用 fake TrajectoryDao
 * 验证字段写入 / query 等价 / fail-open 行为。
 *
 * <p>不依赖真 Room(JVM 单测无 SQLite native),只验证 Repository ↔ DAO 接口契约。
 *
 * <p>RoomAuditRepository 改为同步阻塞 persist,
 * 不再注入 Executor。本测试同步路径下"任务返回后立即查询"无竞态。
 */
public final class RoomAuditRepositoryContractTest {

    @Test
    public void persistWritesAllFields() {
        FakeTrajectoryDao dao = new FakeTrajectoryDao();
        RoomAuditRepository repo = new RoomAuditRepository(dao);

        Trajectory trajectory = new Trajectory(1000L);
        trajectory.finish(StopReason.NO_TOOL_CALL, 500L, 0);
        AgentOutcome outcome = new AgentOutcome("req-A", TaskState.SUCCEEDED,
                StopReason.NO_TOOL_CALL, trajectory, 500L);
        AgentRequest request = AgentRequest.builder("查电量", Actor.DRIVER)
                .sessionId("session-x")
                .arbitrationKey("demo-vehicle")
                .occupantZone(VehicleZone.DRIVER)
                .build();

        repo.persist(AuditOutcomeEntryFactory.from(outcome, request));

        assertEquals(1, dao.store.size());
        TrajectoryEntity entity = dao.store.get("req-A");
        assertEquals("req-A", entity.requestId);
        assertEquals("session-x", entity.sessionId);
        assertEquals("demo-vehicle", entity.arbitrationKey);
        assertEquals("DRIVER", entity.actor);
        assertEquals("DRIVER", entity.zone);
        assertEquals("demo-driver", entity.userId);
        assertEquals(1000L, entity.startedMs);
        assertEquals(500L, entity.durationMs);
        assertEquals(0, entity.iterationCount);
        assertEquals("NO_TOOL_CALL", entity.stopReason);
        assertEquals("SUCCEEDED", entity.finalState);
        assertTrue(entity.trajectoryJson.contains("req-A"));
    }

    @Test
    public void queryByRequestReturnsRecord() {
        FakeTrajectoryDao dao = new FakeTrajectoryDao();
        RoomAuditRepository repo = new RoomAuditRepository(dao);

        Trajectory trajectory = new Trajectory(2000L);
        trajectory.finish(StopReason.NO_TOOL_CALL, 100L, 0);
        AgentOutcome outcome = new AgentOutcome("req-B", TaskState.SUCCEEDED,
                StopReason.NO_TOOL_CALL, trajectory, 100L);
        AgentRequest request = AgentRequest.builder("查胎压", Actor.PASSENGER)
                .sessionId("session-y")
                .arbitrationKey("demo-vehicle")
                .occupantZone(VehicleZone.PASSENGER)
                .build();
        repo.persist(AuditOutcomeEntryFactory.from(outcome, request));

        AuditRecord record = repo.queryByRequest("demo-passenger", "PASSENGER", "req-B");

        assertNotNull(record);
        assertEquals("req-B", record.getRequestId());
        assertEquals("session-y", record.getSessionId());
        assertEquals("PASSENGER", record.getActor());
        assertEquals("demo-passenger", record.getUserId());
    }

    @Test
    public void queryByMissingRequestReturnsNull() {
        FakeTrajectoryDao dao = new FakeTrajectoryDao();
        RoomAuditRepository repo = new RoomAuditRepository(dao);

        AuditRecord record = repo.queryByRequest("any-user", "any-zone", "nonexistent");
        assertNull(record);
    }

    @Test
    public void queryBySessionReturnsOrderedList() {
        FakeTrajectoryDao dao = new FakeTrajectoryDao();
        RoomAuditRepository repo = new RoomAuditRepository(dao);

        // 插入 3 条同 (userId, zone, session) 的记录
        for (int i = 0; i < 3; i++) {
            Trajectory trajectory = new Trajectory(1000L + i * 1000L);  // 1000, 2000, 3000
            trajectory.finish(StopReason.NO_TOOL_CALL, 100L, 0);
            AgentOutcome outcome = new AgentOutcome("req-" + i, TaskState.SUCCEEDED,
                    StopReason.NO_TOOL_CALL, trajectory, 100L);
            AgentRequest request = AgentRequest.builder("查询", Actor.DRIVER)
                    .sessionId("session-z")
                    .arbitrationKey("demo-vehicle")
                    .occupantZone(VehicleZone.DRIVER)
                    .build();
            repo.persist(AuditOutcomeEntryFactory.from(outcome, request));
        }

        // 访问域 (userId, zone) 强制传入
        List<AuditRecord> records = repo.queryBySession(
                "demo-driver", "DRIVER", "session-z", 10);

        assertEquals(3, records.size());
        // 倒序(startedMs 大的在前)
        assertEquals(3000L, records.get(0).getStartedMs());
        assertEquals(1000L, records.get(2).getStartedMs());
    }

    /**
     * 新增:同 sessionId 但访问域不匹配时,必须返回空。
     * 防 UI 层仅凭 sessionId 跨 user/zone 取记录。
     */
    @Test
    public void queryBySessionReturnsEmptyWhenZoneMismatch() {
        FakeTrajectoryDao dao = new FakeTrajectoryDao();
        RoomAuditRepository repo = new RoomAuditRepository(dao);

        Trajectory trajectory = new Trajectory(1000L);
        trajectory.finish(StopReason.NO_TOOL_CALL, 100L, 0);
        AgentOutcome outcome = new AgentOutcome("req-zone", TaskState.SUCCEEDED,
                StopReason.NO_TOOL_CALL, trajectory, 100L);
        AgentRequest request = AgentRequest.builder("查询", Actor.DRIVER)
                .sessionId("session-zone")
                .arbitrationKey("demo-vehicle")
                .occupantZone(VehicleZone.DRIVER)
                .build();
        repo.persist(AuditOutcomeEntryFactory.from(outcome, request));

        // 同 userId 但 zone 不匹配——必须返回空
        List<AuditRecord> wrongZone = repo.queryBySession(
                "demo-driver", "PASSENGER", "session-zone", 10);
        assertEquals("zone 不匹配绝不能取到 DRIVER 写入的记录",
                0, wrongZone.size());

        // 同 zone 但 userId 不匹配——必须返回空
        List<AuditRecord> wrongUser = repo.queryBySession(
                "demo-passenger", "DRIVER", "session-zone", 10);
        assertEquals("userId 不匹配绝不能取到 demo-driver 写入的记录",
                0, wrongUser.size());

        // 完全匹配——必须返回 1 条
        List<AuditRecord> match = repo.queryBySession(
                "demo-driver", "DRIVER", "session-zone", 10);
        assertEquals(1, match.size());
    }

    @Test
    public void failOpenOnDaoException() {
        FakeTrajectoryDao dao = new FakeTrajectoryDao();
        dao.throwOnNextInsert = true;
        RoomAuditRepository repo = new RoomAuditRepository(dao);

        Trajectory trajectory = new Trajectory(5000L);
        trajectory.finish(StopReason.NO_TOOL_CALL, 100L, 0);
        AgentOutcome outcome = new AgentOutcome("req-fail", TaskState.SUCCEEDED,
                StopReason.NO_TOOL_CALL, trajectory, 100L);
        AgentRequest request = AgentRequest.builder("查询", Actor.DRIVER)
                .sessionId("session-fail")
                .arbitrationKey("demo-vehicle")
                .occupantZone(VehicleZone.DRIVER)
                .build();

        // fail-open:不抛异常
        repo.persist(AuditOutcomeEntryFactory.from(outcome, request));
        assertEquals(0, dao.store.size());
    }

    @Test
    public void constructorRejectsNull() {
        try {
            new RoomAuditRepository(null);
            org.junit.Assert.fail("null dao 必须拒绝");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    /**
     * 新增:persist 同步阻塞契约——
     * persist 返回时,query 必须立即看到记录(无竞态)。
     */
    @Test
    public void persistIsSynchronousQuerySeesItImmediately() {
        FakeTrajectoryDao dao = new FakeTrajectoryDao();
        RoomAuditRepository repo = new RoomAuditRepository(dao);

        Trajectory trajectory = new Trajectory(7000L);
        trajectory.finish(StopReason.NO_TOOL_CALL, 100L, 0);
        AgentOutcome outcome = new AgentOutcome("req-sync", TaskState.SUCCEEDED,
                StopReason.NO_TOOL_CALL, trajectory, 100L);
        AgentRequest request = AgentRequest.builder("查电量", Actor.DRIVER)
                .sessionId("session-sync")
                .arbitrationKey("demo-vehicle")
                .occupantZone(VehicleZone.DRIVER)
                .build();

        repo.persist(AuditOutcomeEntryFactory.from(outcome, request));

        // 同步契约:persist 返回后立即查询必须看到记录
        AuditRecord record = repo.queryByRequest("demo-driver", "DRIVER", "req-sync");
        assertNotNull("persist 必须同步落库,query 立即可见", record);
        assertEquals("req-sync", record.getRequestId());
    }

    /** Fake TrajectoryDao:in-memory Map,模拟 Room insert + query 行为。 */
    private static final class FakeTrajectoryDao implements com.matrix.agent.data.db.TrajectoryDao {
        @Override public int deleteByUser(String userId) { return 0; }

        final Map<String, TrajectoryEntity> store = new LinkedHashMap<>();
        boolean throwOnNextInsert = false;

        @Override
        public void insert(TrajectoryEntity entity) {
            if (throwOnNextInsert) {
                throwOnNextInsert = false;
                throw new RuntimeException("fake DAO insert failed");
            }
            store.put(entity.requestId, entity);
        }

        @Override
        public TrajectoryEntity queryByRequestScoped(String userId, String zone, String requestId) {
            // 测试 fake 不做 scope 过滤——真实 RoomAuditRepository 走 SQL WHERE userId+zone 过滤
            TrajectoryEntity entity = store.get(requestId);
            if (entity == null) return null;
            if (!entity.userId.equals(userId) || !entity.zone.equals(zone)) return null;
            return entity;
        }

        @Override
        public List<TrajectoryEntity> queryBySessionScoped(String userId, String zone,
                String sessionId, int limit) {
            List<TrajectoryEntity> matching = new ArrayList<>();
            for (TrajectoryEntity entity : store.values()) {
                if (userId.equals(entity.userId) && zone.equals(entity.zone)
                        && sessionId.equals(entity.sessionId)) {
                    matching.add(entity);
                }
            }
            // 按 startedMs 倒序
            matching.sort((a, b) -> Long.compare(b.startedMs, a.startedMs));
            if (matching.size() > limit) {
                return new ArrayList<>(matching.subList(0, limit));
            }
            return matching;
        }

        @Override
        public int deleteByUserZone(String userId, String zone) {
            int removed = 0;
            for (String key : new ArrayList<>(store.keySet())) {
                TrajectoryEntity e = store.get(key);
                if (userId.equals(e.userId) && zone.equals(e.zone)) {
                    store.remove(key);
                    removed++;
                }
            }
            return removed;
        }
    }
}
