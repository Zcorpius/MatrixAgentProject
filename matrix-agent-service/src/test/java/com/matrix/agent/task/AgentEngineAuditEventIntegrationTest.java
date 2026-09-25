package com.matrix.agent.task;
import com.matrix.agent.task.redact.*;

import com.matrix.agent.demo.DemoModelGateway;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.Before;
import org.junit.Test;

import com.matrix.agent.task.capability.CapabilityRegistry;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.data.memory.InMemoryMemoryStore;
import com.matrix.agent.task.policy.PolicyEngine;
import com.matrix.agent.session.SessionLockManager;
import com.matrix.agent.session.SessionManager;
import com.matrix.agent.demo.MockCapabilityProvider;
import com.matrix.agent.task.tool.ToolExecutor;
import com.matrix.agent.data.audit.AuditEventRecorder;
import com.matrix.agent.data.audit.AuditEventTypes;
import com.matrix.agent.data.db.AuditEventDao;
import com.matrix.agent.data.db.AuditEventEntity;

/**
 * AgentEngine 4 个出口点埋点测试。
 *
 * <p>注入 fake AuditEventDao + 同步 Executor + AuditEventRecorder 到 AgentEngine,
 * 跑一个完整任务后验证 audit_event 表写入正确:
 * <ul>
 *   <li>2 个 Tool 调用 = 2 PRE_TOOL + 2 POST_TOOL;</li>
 *   <li>每个 PRE_TOOL 含 cap name / 脱敏 args;</li>
 *   <li>每个 POST_TOOL 含 status / verified / durationMs;</li>
 *   <li>userId / zone / actor 从 request 正确推导。</li>
 * </ul>
 *
 * <p>POLICY / STEER 路径由 specialist 测试覆盖(需 mock policy reject / steer mailbox)。
 */
public final class AgentEngineAuditEventIntegrationTest {
    private InMemoryMemoryStore memoryStore;
    private CapabilityRegistry registry;
    private MockCapabilityProvider provider;
    private SessionManager sessionManager;
    private SessionLockManager sessionLockManager;
    private ToolExecutor toolExecutor;
    private ModelCallExecutor modelCallExecutor;
    private FakeAuditEventDao auditEventDao;

    @Before
    public void setUp() {
        memoryStore = new InMemoryMemoryStore();
        registry = CapabilityRegistry.createDemoRegistry();
        provider = new MockCapabilityProvider(memoryStore);
        sessionManager = new SessionManager();
        sessionLockManager = new SessionLockManager();
        toolExecutor = new ToolExecutor(4);
        modelCallExecutor = new ModelCallExecutor(2);
        auditEventDao = new FakeAuditEventDao();
    }

    @Test
    public void preToolAndPostToolWrittenForEachToolCall() {
        AgentEngine engine = newEngine(new AuditEventRecorder(auditEventDao, immediateExecutor()));

        AgentOutcome outcome = engine.execute(new AgentRequest(
                "把主驾温度调到24度，然后导航回家", Actor.DRIVER));

        assertEquals(TaskState.SUCCEEDED, outcome.getFinalState());
        assertEquals(2, outcome.getResults().size());

        // 任务跑完——audit_event 应有 2 PRE_TOOL + 2 POST_TOOL = 4 行
        int preToolCount = 0;
        int postToolCount = 0;
        for (AuditEventEntity e : auditEventDao.store) {
            if (AuditEventTypes.PRE_TOOL.equals(e.type)) preToolCount++;
            if (AuditEventTypes.POST_TOOL.equals(e.type)) postToolCount++;
        }
        assertEquals("2 个 Tool 调用 = 2 PRE_TOOL", 2, preToolCount);
        assertEquals("2 个 Tool 调用 = 2 POST_TOOL", 2, postToolCount);
    }

    @Test
    public void auditEventContainsCorrectUserIdAndZone() {
        AgentEngine engine = newEngine(new AuditEventRecorder(auditEventDao, immediateExecutor()));

        engine.execute(new AgentRequest("把主驾温度调到24度", Actor.DRIVER));

        assertTrue("至少 1 行 audit event", auditEventDao.store.size() >= 1);
        for (AuditEventEntity e : auditEventDao.store) {
            assertEquals("demo-driver", e.userId);
            assertEquals("DRIVER", e.zone);
            assertEquals("DRIVER", e.actor);
        }
    }

    @Test
    public void auditEventRecorderNullDoesNotBreakEngine() {
        AgentEngine engine = newEngine(null);  // 未配置时退化为 NOOP

        AgentOutcome outcome = engine.execute(new AgentRequest(
                "把主驾温度调到24度", Actor.DRIVER));

        assertEquals(TaskState.SUCCEEDED, outcome.getFinalState());
        assertEquals("NOOP recorder 不写 audit_event", 0, auditEventDao.store.size());
    }

    @Test
    public void preToolPayloadRedactsToolArgs() {
        AgentEngine engine = newEngine(new AuditEventRecorder(auditEventDao, immediateExecutor()));

        // "导航回家"——args 含 destination,必须脱敏后写 audit_event
        engine.execute(new AgentRequest("导航回家", Actor.DRIVER));

        AuditEventEntity preToolEvent = auditEventDao.store.stream()
                .filter(e -> AuditEventTypes.PRE_TOOL.equals(e.type))
                .findFirst()
                .orElseThrow(() -> new AssertionError("应有 PRE_TOOL 事件"));
        assertTrue("PRE_TOOL payload 必须含 tool name",
                preToolEvent.payloadJson.contains("tool"));
        // 不暴露"回家"原文——args 已被 AuditRedactor 脱敏成 [destination redacted]
        assertTrue("PRE_TOOL 不含原始 destination 值,实际:" + preToolEvent.payloadJson,
                !preToolEvent.payloadJson.contains("回家"));
    }

    private AgentEngine newEngine(AuditEventRecorder recorder) {
        return new AgentEngine(
                new DemoModelGateway(), modelCallExecutor, new PolicyEngine(registry),
                registry, provider, sessionManager,
                new DefaultContextUpdater(), sessionLockManager, toolExecutor, new AgentBudget(), null,
                new AgentEngineConfiguration.Builder().auditEventRecorder(recorder).build());
    }

    private static ExecutorService immediateExecutor() {
        return new ImmediateExecutorService();
    }

    private static final class ImmediateExecutorService extends AbstractExecutorService {
        @Override public void execute(Runnable command) { command.run(); }
        @Override public void shutdown() {}
        @Override public List<Runnable> shutdownNow() { return new ArrayList<>(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }

    private static final class FakeAuditEventDao implements AuditEventDao {
        @Override public int deleteByUser(String userId) { return 0; }

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
}
