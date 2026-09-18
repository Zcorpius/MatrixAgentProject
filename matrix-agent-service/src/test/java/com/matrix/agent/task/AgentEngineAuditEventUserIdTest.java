package com.matrix.agent.task;

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
import com.matrix.agent.identity.ActorUsers;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.data.memory.InMemoryMemoryStore;
import com.matrix.agent.task.policy.PolicyEngine;
import com.matrix.agent.session.SessionLockManager;
import com.matrix.agent.session.SessionManager;
import com.matrix.agent.demo.MockCapabilityProvider;
import com.matrix.agent.task.tool.ToolExecutor;
import com.matrix.agent.data.audit.AuditEventRecorder;
import com.matrix.agent.data.db.AuditEventDao;
import com.matrix.agent.data.db.AuditEventEntity;

/**
 * audit_event.userId 必须用 ActorUsers.userIdOf,而非 sessionId。
 *
 * <p>评审发现 AgentEngine 4 处 recordXxx 都用 request.getSessionId()。
 * demo 中 sessionId 默认 = "demo-driver" 与 userId 字面量相等,掩盖了问题。
 * 一旦 AAOS 多会话(sessionId 是任意 UUID)上线,audit_event.userId 列写入 sessionId,
 * Repository.clearAuditSafe 按 userId 清理失效。
 *
 * <p>本测试故意构造 sessionId="session-xyz-999" + Actor.DRIVER——验证 audit_event.userId
 * 仍正确写入 "demo-driver"(ActorUsers.USER_DRIVER)而非 "session-xyz-999"。
 */
public final class AgentEngineAuditEventUserIdTest {
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
    public void userIdIsActorMappedNotSessionId() {
        AgentEngine engine = newEngine(new AuditEventRecorder(auditEventDao, immediateExecutor()));

        AgentRequest request = AgentRequest.builder("把主驾温度调到24度", Actor.DRIVER)
                .sessionId("session-xyz-999")
                .build();
        assertEquals("测试前置——sessionId 不等于 userId 字面量",
                "session-xyz-999", request.getSessionId());

        engine.execute(request);

        assertTrue("任务至少产生 1 行 audit event", auditEventDao.store.size() >= 1);
        for (AuditEventEntity e : auditEventDao.store) {
            assertEquals("audit_event.userId 必须是 ActorUsers.userIdOf 结果,而非 sessionId",
                    ActorUsers.USER_DRIVER, e.userId);
            assertTrue("audit_event.userId 不能等于 sessionId", !"session-xyz-999".equals(e.userId));
        }
    }

    @Test
    public void passengerUserIdCorrectlyMapped() {
        AgentEngine engine = newEngine(new AuditEventRecorder(auditEventDao, immediateExecutor()));

        AgentRequest request = AgentRequest.builder("导航回家", Actor.PASSENGER)
                .sessionId("another-session-456")
                .build();

        engine.execute(request);

        for (AuditEventEntity e : auditEventDao.store) {
            assertEquals("PASSENGER → demo-passenger", ActorUsers.USER_PASSENGER, e.userId);
            assertEquals("PASSENGER", e.zone);
        }
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
