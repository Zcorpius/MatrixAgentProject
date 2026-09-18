package com.matrix.agent.task;

import com.matrix.agent.demo.DemoModelGateway;

import com.matrix.agent.contract.ModelTurn;

import com.matrix.agent.contract.ModelGateway;

import com.matrix.agent.contract.FinishReason;

import com.matrix.agent.task.port.TaskMemoryWriter;

import com.matrix.agent.task.capability.CapabilityRegistry;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.identity.CancellationToken;
import com.matrix.agent.data.memory.InMemoryMemoryStore;
import com.matrix.agent.task.policy.PolicyEngine;
import com.matrix.agent.session.SessionLockManager;
import com.matrix.agent.session.SessionManager;
import com.matrix.agent.demo.MockCapabilityProvider;
import com.matrix.agent.task.tool.ToolExecutor;
import com.matrix.agent.data.audit.AuditRecord;
import com.matrix.agent.data.audit.AuditRepository;

import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

/**
 * AgentEngine setter 注入 MemoryWriter 后,主出口 + terminalOutcome
 * 出口都调 writeEpisodicOnTerminal 一次。
 *
 * <p>沿用 AgentEngineAuditSinkTest 模式——注入 {@link CapturingMemoryWriter} 计数调用,
 * 触发:
 * <ol>
 *   <li>主出口(SUCCEEDED)—— happy path "查电量"</li>
 *   <li>pre-loop terminal(CANCELLED)—— 预先 cancel token</li>
 *   <li>主出口 protocol error(PROTOCOL_ERROR)—— NONE finishReason</li>
 * </ol>
 *
 * <p>不验证 session-lock-timeout / interrupted 路径——这两条是 terminalOutcome 内部
 * 走的另一分支,而本测试目的是验证 setter 注入 + 两个 hook 点都被调,不重复 audit 那套
 * 并发断言。
 */
public final class AgentEngineEpisodicWriteHookTest {
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

    @Test
    public void mainExitTriggersWriteEpisodicOnSucceeded() {
        CapturingMemoryWriter writer = new CapturingMemoryWriter();
        AgentEngine engine = newEngine(new DemoModelGateway(), writer);

        AgentOutcome outcome = engine.execute(AgentRequest.builder("查电量", Actor.DRIVER)
                .sessionId("happy-path").build());

        assertEquals(TaskState.SUCCEEDED, outcome.getFinalState());
        assertEquals("main exit 调 writeEpisodicOnTerminal 1 次",
                1, writer.episodicCount.get());
        assertNotNull(writer.lastOutcome);
        assertEquals(outcome.getRequestId(), writer.lastOutcome.getRequestId());
    }

    @Test
    public void preLoopTerminalTriggersWriteEpisodicOnCancelled() {
        CapturingMemoryWriter writer = new CapturingMemoryWriter();
        AgentEngine engine = newEngine(new DemoModelGateway(), writer);
        CancellationToken token = new CancellationToken();
        token.cancel();

        AgentOutcome outcome = engine.execute(AgentRequest.builder("查电量", Actor.DRIVER)
                .sessionId("pre-loop-cancel")
                .cancellationToken(token)
                .build());

        assertEquals(TaskState.CANCELLED, outcome.getFinalState());
        assertEquals(StopReason.CANCELLED, outcome.getStopReason());
        assertEquals("terminalOutcome 也调 writeEpisodicOnTerminal 1 次",
                1, writer.episodicCount.get());
        assertNotNull(writer.lastOutcome);
        assertEquals(TaskState.CANCELLED, writer.lastOutcome.getFinalState());
    }

    @Test
    public void protocolErrorAlsoTriggersWriteEpisodic() {
        CapturingMemoryWriter writer = new CapturingMemoryWriter();
        ModelGateway gateway = req -> ModelTurn.of("oops", FinishReason.NONE);
        AgentEngine engine = newEngine(gateway, writer);

        AgentOutcome outcome = engine.execute(AgentRequest.builder("查电量", Actor.DRIVER)
                .sessionId("protocol-error").build());

        assertEquals(StopReason.PROTOCOL_ERROR, outcome.getStopReason());
        assertEquals("protocol-error main exit 也调 writeEpisodicOnTerminal",
                1, writer.episodicCount.get());
    }

    @Test
    public void defaultMemoryWriterIsNoOpSafe() {
        AgentEngine engine = newEngine(new DemoModelGateway(), null);
        // 不调 setMemoryWriter —— 默认 NOOP,不抛
        AgentOutcome outcome = engine.execute(AgentRequest.builder("查电量", Actor.DRIVER)
                .sessionId("default-noop").build());
        assertEquals(TaskState.SUCCEEDED, outcome.getFinalState());
    }

    private AgentEngine newEngine(ModelGateway gateway, TaskMemoryWriter writer) {
        return new AgentEngine(gateway, modelCallExecutor, new PolicyEngine(registry),
                registry, provider, sessionManager, new DefaultContextUpdater(),
                sessionLockManager, toolExecutor, new AgentBudget(), null,
                new AgentEngineConfiguration.Builder()
                        .taskMemoryWriter(writer)
                        .build());
    }

    private static final class CapturingMemoryWriter implements TaskMemoryWriter {
        final AtomicInteger episodicCount = new AtomicInteger();
        volatile AgentOutcome lastOutcome;
        volatile long lastRequestEpoch = -1L;

        @Override
        public void writeEpisodicOnTerminal(AgentRequest request, AgentOutcome outcome, long requestEpoch) {
            episodicCount.incrementAndGet();
            lastOutcome = outcome;
            lastRequestEpoch = requestEpoch;
        }

    }

    private static final class NoopAuditRepository implements AuditRepository {
        @Override
        public void persist(com.matrix.agent.data.audit.AuditOutcomeEntry entry) { }
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
}
