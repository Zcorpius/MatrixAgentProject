package com.matrix.agent.task;

import com.matrix.agent.task.capability.CapabilityProvider;
import com.matrix.agent.task.capability.CapabilityRegistry;
import com.matrix.agent.task.identity.Actor;
import com.matrix.agent.task.identity.AgentRequest;
import com.matrix.agent.task.identity.CancellationToken;
import com.matrix.agent.data.memory.InMemoryMemoryStore;
import com.matrix.agent.task.policy.PolicyEngine;
import com.matrix.agent.data.session.SessionLockManager;
import com.matrix.agent.data.session.SessionManager;
import com.matrix.agent.task.tool.MockCapabilityProvider;
import com.matrix.agent.task.tool.ToolCall;
import com.matrix.agent.task.tool.ToolExecutor;
import com.matrix.agent.task.tool.ToolResult;
import com.matrix.agent.data.audit.AuditRecord;
import com.matrix.agent.data.audit.AuditRepository;

import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * AgentEngine 5 个出口点的 Audit 持久化契约测试。
 *
 * <p>注入 {@link CapturingAuditRepository} 捕获每次 persist(outcome, request) 调用,
 * 分别触发:
 * <ol>
 *   <li>session lock acquire timeout(L173) → terminalOutcome(TIMED_OUT, TIMEOUT)</li>
 *   <li>InterruptedException(L181) → terminalOutcome(CANCELLED, CANCELLED)</li>
 *   <li>pre-loop terminal(L201) → request 预先 cancel/deadline</li>
 *   <li>主出口(L535) → happy path SUCCEEDED</li>
 * </ol>
 *
 * <p>出口点 5(terminalOutcome 方法本身)被 1+2 覆盖——它俩都调 terminalOutcome。
 * 断言每个出口都调了 persist 一次,outcome 字段完整(requestId / finalState / stopReason /
 * trajectory.stopReason / trajectory.iterations)。
 */
public final class AgentEngineAuditSinkTest {
    private InMemoryMemoryStore memoryStore;
    private MockCapabilityProvider provider;
    private CapabilityRegistry registry;
    private SessionManager sessionManager;
    private SessionLockManager sessionLockManager;
    private ToolExecutor toolExecutor;
    private ModelCallExecutor modelCallExecutor;

    @Before
    public void setUp() {
        memoryStore = new InMemoryMemoryStore();
        registry = CapabilityRegistry.createDemoRegistry();
        provider = new MockCapabilityProvider(memoryStore);
        sessionManager = new SessionManager();
        sessionLockManager = new SessionLockManager();
        toolExecutor = new ToolExecutor(4);
        modelCallExecutor = new ModelCallExecutor(2);
    }

    /** 出口点 4:主出口 happy path——SUCCEEDED 必须调 persist 一次。 */
    @Test
    public void mainExitPersistsAudit() {
        CapturingAuditRepository audit = new CapturingAuditRepository();
        AgentEngine engine = newEngine(new DemoModelGateway(), audit);

        AgentOutcome outcome = engine.execute(AgentRequest.builder("查电量", Actor.DRIVER)
                .sessionId("main-exit").build());

        assertEquals(TaskState.SUCCEEDED, outcome.getFinalState());
        assertEquals(1, audit.persistCount.get());
        AuditCapture capture = audit.lastCapture;
        assertNotNull(capture);
        assertEquals(outcome.getRequestId(), capture.outcome.getRequestId());
        assertEquals(outcome.getFinalState(), capture.outcome.getFinalState());
        assertEquals(outcome.getStopReason(), capture.outcome.getStopReason());
        assertSame(outcome.getTrajectory(), capture.outcome.getTrajectory());
        assertEquals("main-exit", capture.request.getSessionId());
    }

    /** 出口点 3:pre-loop terminal——request 预先 cancel。 */
    @Test
    public void preLoopTerminalPersistsAudit() {
        CapturingAuditRepository audit = new CapturingAuditRepository();
        AgentEngine engine = newEngine(new DemoModelGateway(), audit);
        CancellationToken token = new CancellationToken();
        token.cancel();

        AgentOutcome outcome = engine.execute(AgentRequest.builder("查电量", Actor.DRIVER)
                .sessionId("pre-loop")
                .cancellationToken(token)
                .build());

        assertEquals(TaskState.CANCELLED, outcome.getFinalState());
        assertEquals(StopReason.CANCELLED, outcome.getStopReason());
        assertEquals(1, audit.persistCount.get());
        AuditCapture capture = audit.lastCapture;
        assertNotNull(capture);
        assertEquals(TaskState.CANCELLED, capture.outcome.getFinalState());
        assertEquals(StopReason.CANCELLED, capture.outcome.getStopReason());
        assertEquals(0, capture.outcome.getTrajectory().getTotalToolCalls());
    }

    /**
     * 出口点 1:session lock acquire timeout。
     *
     * <p>另一线程预先持有同 session lock 且不释放(用 latch 阻塞 close),
     * 本线程 execute(remaining=100ms) → tryAcquire 返回 null → terminalOutcome(TIMED_OUT, TIMEOUT)。
     */
    @Test
    public void sessionLockTimeoutPersistsAudit() throws InterruptedException {
        CapturingAuditRepository audit = new CapturingAuditRepository();
        AgentEngine engine = newEngine(new DemoModelGateway(), audit);
        String sessionId = "lock-timeout";

        CountDownLatch holderAcquired = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try {
                SessionLockManager.Handle handle = sessionLockManager.tryAcquire(sessionId, 1_000);
                if (handle != null) {
                    holderAcquired.countDown();
                    releaseHolder.await(5, TimeUnit.SECONDS);
                    handle.close();
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        holder.setDaemon(true);
        holder.start();
        assertTrue("holder 应在 5s 内拿到锁", holderAcquired.await(5, TimeUnit.SECONDS));

        try {
            AgentOutcome outcome = engine.execute(AgentRequest.builder("查电量", Actor.DRIVER)
                    .sessionId(sessionId)
                    .timeoutMillis(100L)
                    .build());

            assertEquals(TaskState.TIMED_OUT, outcome.getFinalState());
            assertEquals(StopReason.TIMEOUT, outcome.getStopReason());
            assertEquals("session lock timeout 必须落 Audit", 1, audit.persistCount.get());
            AuditCapture capture = audit.lastCapture;
            assertNotNull(capture);
            assertEquals(TaskState.TIMED_OUT, capture.outcome.getFinalState());
            assertEquals(StopReason.TIMEOUT, capture.outcome.getStopReason());
        } finally {
            releaseHolder.countDown();
            holder.join(5_000);
        }
    }

    /**
     * 出口点 2:InterruptedException 路径。
     *
     * <p>线程预先 interrupt(),tryAcquire 内部 lock.tryLock(time, unit) 在阻塞时检测到
     * interrupt 抛 InterruptedException → execute catch → terminalOutcome(CANCELLED, CANCELLED)。
     * 同样需要并发持有锁让 tryAcquire 进入阻塞状态(否则不阻塞就拿到锁,interrupt flag 失效)。
     */
    @Test
    public void interruptedDuringLockAcquirePersistsAudit() throws InterruptedException {
        CapturingAuditRepository audit = new CapturingAuditRepository();
        // 关闭 DemoModelGateway 干扰,本测试不依赖 gateway 决策
        AgentEngine engine = newEngine(new DemoModelGateway(), audit);
        String sessionId = "lock-interrupted";

        CountDownLatch holderAcquired = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try {
                SessionLockManager.Handle handle = sessionLockManager.tryAcquire(sessionId, 5_000);
                if (handle != null) {
                    holderAcquired.countDown();
                    releaseHolder.await(10, TimeUnit.SECONDS);
                    handle.close();
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        holder.setDaemon(true);
        holder.start();
        assertTrue("holder 应在 5s 内拿到锁", holderAcquired.await(5, TimeUnit.SECONDS));

        try {
            AtomicInteger resultRef = new AtomicInteger();
            CountDownLatch executeDone = new CountDownLatch(1);

            // runner 在 execute 前 self-interrupt——tryAcquire 立即抛 InterruptedException
            Thread runner = new Thread(() -> {
                Thread.currentThread().interrupt();
                try {
                    AgentOutcome outcome = engine.execute(AgentRequest.builder("查电量", Actor.DRIVER)
                            .sessionId(sessionId)
                            .timeoutMillis(2_000L)
                            .build());
                    resultRef.set(outcome.getFinalState().ordinal());
                } finally {
                    executeDone.countDown();
                }
            });
            runner.setDaemon(true);
            runner.start();
            assertTrue("execute 应在 10s 内返回", executeDone.await(10, TimeUnit.SECONDS));
            runner.join(2_000);

            assertEquals("InterruptedException 路径必须 CANCELLED",
                    TaskState.CANCELLED.ordinal(), resultRef.get());
            assertEquals("InterruptedException 必须落 Audit", 1, audit.persistCount.get());
            AuditCapture capture = audit.lastCapture;
            assertNotNull(capture);
            assertEquals(TaskState.CANCELLED, capture.outcome.getFinalState());
            assertEquals(StopReason.CANCELLED, capture.outcome.getStopReason());
        } finally {
            releaseHolder.countDown();
            holder.join(5_000);
        }
    }

    /**
     * Optional 终态(无 tool call / MAX_ITERATIONS)也走 persist——
     * 这是主出口的另一变体,验证非 SUCCEEDED 终态也落库。
     */
    @Test
    public void mainExitPersistsAuditOnProtocolError() {
        CapturingAuditRepository audit = new CapturingAuditRepository();
        // gateway 返回 NONE finishReason → PROTOCOL_ERROR 终止
        ModelGateway gateway = req -> ModelTurn.of("oops", FinishReason.NONE);
        AgentEngine engine = newEngine(gateway, audit);

        AgentOutcome outcome = engine.execute(AgentRequest.builder("查电量", Actor.DRIVER)
                .sessionId("protocol-error").build());

        assertEquals(StopReason.PROTOCOL_ERROR, outcome.getStopReason());
        assertEquals(1, audit.persistCount.get());
        AuditCapture capture = audit.lastCapture;
        assertNotNull(capture);
        assertEquals(StopReason.PROTOCOL_ERROR, capture.outcome.getStopReason());
        // trajectory.iterations 必须保留(已落 audit)
        assertEquals(1, capture.outcome.getTrajectory().getIterations().size());
    }

    private AgentEngine newEngine(ModelGateway gateway, AuditRepository audit) {
        return new AgentEngine(gateway, modelCallExecutor, new PolicyEngine(registry),
                registry, provider, sessionManager, new DefaultContextUpdater(),
                sessionLockManager, toolExecutor, new AgentBudget(), null,
                new AgentEngineConfiguration.Builder().auditSink(audit::persist).build());
    }

    /** 简易 fake:捕获最近一次 persist(outcome, request),计数全部调用。 */
    private static final class CapturingAuditRepository implements AuditRepository {
        final AtomicInteger persistCount = new AtomicInteger();
        volatile AuditCapture lastCapture;

        @Override
        public void persist(AgentOutcome outcome, AgentRequest request) {
            persistCount.incrementAndGet();
            lastCapture = new AuditCapture(outcome, request);
        }

        @Override
        public AuditRecord queryByRequest(String userId, String zone, String requestId) {
            return null;
        }

        @Override
        public List<AuditRecord> queryBySession(String userId, String zone,
                String sessionId, int limit) {
            return Collections.emptyList();
        }
    }

    private static final class AuditCapture {
        final AgentOutcome outcome;
        final AgentRequest request;

        AuditCapture(AgentOutcome outcome, AgentRequest request) {
            this.outcome = outcome;
            this.request = request;
        }
    }
}
