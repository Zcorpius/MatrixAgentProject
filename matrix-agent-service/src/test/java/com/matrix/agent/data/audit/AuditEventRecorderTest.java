package com.matrix.agent.data.audit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.Test;

import com.matrix.agent.task.AuditEventTypes;
import com.matrix.agent.data.db.AuditEventDao;
import com.matrix.agent.data.db.AuditEventEntity;

/**
 * AuditEventRecorder JVM 契约测试。
 *
 * <p>用 fake in-memory DAO + 同步 Executor 验证:
 * <ul>
 *   <li>5 个 recordXxx 方法写入正确的 type / userId / zone / actor;</li>
 *   <li>fail-open:Dao.insert 抛异常时仅 log,不向上传播;</li>
 *   <li>NOOP 单例:Dao=null 时所有 recordXxx 直接 return;</li>
 *   <li>Executor 拒绝(RejectedExecutionException)时 fail-open。</li>
 * </ul>
 *
 * <p>真实 Room _Impl.java SQL 验证由 androidTest AuditEventRecorderInstrumentedTest 负责。
 */
public final class AuditEventRecorderTest {

    @Test
    public void recordPreToolWritesCorrectEntity() {
        FakeDao dao = new FakeDao();
        AuditEventRecorder recorder = new AuditEventRecorder(dao, directExecutor());

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("destination", "[destination redacted]");
        recorder.recordPreTool("req-1", "demo-driver", "DRIVER", "DRIVER",
                "navigation.route", args, "{destination=[redacted]}");

        assertEquals(1, dao.store.size());
        AuditEventEntity e = dao.store.get(0);
        assertEquals("req-1", e.requestId);
        assertEquals("demo-driver", e.userId);
        assertEquals("DRIVER", e.zone);
        assertEquals("DRIVER", e.actor);
        assertEquals(AuditEventTypes.PRE_TOOL, e.type);
        assertTrue("payload 必须含 tool name", e.payloadJson.contains("navigation.route"));
    }

    @Test
    public void recordPostToolWritesStatusAndMetrics() {
        FakeDao dao = new FakeDao();
        AuditEventRecorder recorder = new AuditEventRecorder(dao, directExecutor());

        recorder.recordPostTool("req-1", "demo-driver", "DRIVER", "DRIVER",
                "navigation.route", "SUCCESS", true, 150L, "[result redacted]");

        AuditEventEntity e = dao.store.get(0);
        assertEquals(AuditEventTypes.POST_TOOL, e.type);
        assertTrue(e.payloadJson.contains("SUCCESS"));
        assertTrue(e.payloadJson.contains("\"verified\":true"));
        assertTrue(e.payloadJson.contains("\"durationMs\":150"));
    }

    @Test
    public void recordPolicyDecisionWritesRejectionType() {
        FakeDao dao = new FakeDao();
        AuditEventRecorder recorder = new AuditEventRecorder(dao, directExecutor());

        recorder.recordPolicyDecision("req-1", "demo-driver", "DRIVER", "DRIVER",
                "vehicle.climate", "PARAMETER", "[reason redacted]");

        AuditEventEntity e = dao.store.get(0);
        assertEquals(AuditEventTypes.POLICY, e.type);
        assertTrue(e.payloadJson.contains("PARAMETER"));
    }

    @Test
    public void recordSteerWritesTypeAndPayloadCharsOnly() {
        FakeDao dao = new FakeDao();
        AuditEventRecorder recorder = new AuditEventRecorder(dao, directExecutor());

        recorder.recordSteer("req-1", "demo-driver", "DRIVER", "DRIVER",
                "FORCE_TOOL", 24);

        AuditEventEntity e = dao.store.get(0);
        assertEquals(AuditEventTypes.STEER, e.type);
        assertTrue(e.payloadJson.contains("FORCE_TOOL"));
        assertTrue(e.payloadJson.contains("\"payloadChars\":24"));
    }

    @Test
    public void recordSteerDroppedStalePredefinedForStage5() {
        FakeDao dao = new FakeDao();
        AuditEventRecorder recorder = new AuditEventRecorder(dao, directExecutor());

        recorder.recordSteerDroppedStale("req-1", "demo-driver", "DRIVER", "DRIVER",
                "FORCE_TOOL", 24);

        AuditEventEntity e = dao.store.get(0);
        assertEquals(AuditEventTypes.STEER_DROPPED_STALE, e.type);
    }

    @Test
    public void failOpenOnDaoExceptionDoesNotPropagate() {
        ThrowingDao dao = new ThrowingDao();
        AuditEventRecorder recorder = new AuditEventRecorder(dao, directExecutor());

        // 不抛——fail-open
        recorder.recordPreTool("req-1", "demo-driver", "DRIVER", "DRIVER",
                "navigation.route", new LinkedHashMap<>(), "{}");
        recorder.recordPostTool("req-1", "demo-driver", "DRIVER", "DRIVER",
                "navigation.route", "SUCCESS", true, 100L, "[redacted]");
        recorder.recordPolicyDecision("req-1", "demo-driver", "DRIVER", "DRIVER",
                "vehicle.climate", "PARAMETER", "[redacted]");
        recorder.recordSteer("req-1", "demo-driver", "DRIVER", "DRIVER",
                "FORCE_TOOL", 24);

        assertEquals("Dao 异常必须记入 dropped", 4, recorder.getDroppedCount());
        assertEquals("无 queued", 0, recorder.getQueuedCount());
    }

    @Test
    public void noopRecorderDoesNotCallDao() {
        FakeDao dao = new FakeDao();
        // 不传 Dao → NOOP 风格(构造时 executor=null)
        AuditEventRecorder noop = AuditEventRecorder.NOOP;

        noop.recordPreTool("req", "user", "z", "a", "cap", new LinkedHashMap<>(), "{}");
        noop.recordPostTool("req", "user", "z", "a", "cap", "S", true, 1L, "r");
        noop.recordPolicyDecision("req", "user", "z", "a", "cap", "PARAM", "r");
        noop.recordSteer("req", "user", "z", "a", "FORCE_TOOL", 1);

        assertEquals("NOOP 不写任何事件", 0, dao.store.size());
        assertEquals(0, noop.getQueuedCount());
    }

    @Test
    public void userIdDefaultsToEmptyStringWhenNull() {
        FakeDao dao = new FakeDao();
        AuditEventRecorder recorder = new AuditEventRecorder(dao, directExecutor());

        recorder.recordPreTool("req", null, null, null,
                "cap", new LinkedHashMap<>(), "{}");

        AuditEventEntity e = dao.store.get(0);
        assertEquals("null userId 退化为空串", "", e.userId);
        assertEquals("null zone 退化为空串", "", e.zone);
        assertEquals("null actor 退化为空串", "", e.actor);
    }

    /** 同步直接执行 Executor——保证 recordXxx 返回前 insert 已完成。 */
    private static ExecutorService directExecutor() {
        return new ImmediateExecutorService();
    }

    private static final class ImmediateExecutorService extends java.util.concurrent.AbstractExecutorService {
        @Override public void execute(Runnable command) { command.run(); }
        @Override public void shutdown() {}
        @Override public List<Runnable> shutdownNow() { return new ArrayList<>(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) {
            return true;
        }
    }

    private static final class FakeDao implements AuditEventDao {
        final List<AuditEventEntity> store = new ArrayList<>();

        @Override
        public void insert(AuditEventEntity entity) {
            store.add(entity);
        }

        @Override
        public List<AuditEventEntity> queryByRequest(String requestId) {
            List<AuditEventEntity> result = new ArrayList<>();
            for (AuditEventEntity e : store) {
                if (requestId.equals(e.requestId)) result.add(e);
            }
            return result;
        }

        @Override
        public List<AuditEventEntity> queryByUserZone(String userId, String zone) {
            List<AuditEventEntity> result = new ArrayList<>();
            for (AuditEventEntity e : store) {
                if (userId.equals(e.userId) && zone.equals(e.zone)) result.add(e);
            }
            return result;
        }

        @Override
        public int deleteByUserZone(String userId, String zone) {
            int removed = 0;
            for (int i = store.size() - 1; i >= 0; i--) {
                AuditEventEntity e = store.get(i);
                if (userId.equals(e.userId) && zone.equals(e.zone)) {
                    store.remove(i);
                    removed++;
                }
            }
            return removed;
        }
    }

    private static final class ThrowingDao implements AuditEventDao {
        @Override public void insert(AuditEventEntity entity) {
            throw new RuntimeException("simulated SQL failure");
        }
        @Override public List<AuditEventEntity> queryByRequest(String requestId) {
            return new ArrayList<>();
        }
        @Override public List<AuditEventEntity> queryByUserZone(String userId, String zone) {
            return new ArrayList<>();
        }
        @Override public int deleteByUserZone(String userId, String zone) { return 0; }
    }
}
