package com.matrix.agent.task;

import com.matrix.agent.demo.DemoModelGateway;

import com.matrix.agent.contract.ModelTurn;

import com.matrix.agent.contract.ModelGateway;

import com.matrix.agent.contract.FinishReason;

import com.matrix.agent.task.port.TaskMemoryWriter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Before;
import org.junit.Test;

import com.matrix.agent.task.capability.CapabilityRegistry;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.identity.CancellationToken;
import com.matrix.agent.identity.VehicleZone;
import com.matrix.agent.data.memory.InMemoryMemoryStore;
import com.matrix.agent.task.policy.PolicyEngine;
import com.matrix.agent.session.SessionLockManager;
import com.matrix.agent.session.SessionManager;
import com.matrix.agent.demo.MockCapabilityProvider;
import com.matrix.agent.task.tool.ToolExecutor;
import com.matrix.agent.data.audit.AuditRecord;
import com.matrix.agent.data.audit.AuditRepository;

/**
 * 验证 AgentEngine 3 个 hook 点(L336 pre-loop / L704 主路径 /
 * L812 terminalOutcome)都把 request.getEpoch() 透传到 MemoryWriter。
 *
 * <p>用户硬约束:epoch 必须与数据库写操作同事务。AgentEngine 不直接做 epoch 校验,
 * 但必须**透传正确的 epoch 值**给 RoomMemoryWriter,后者在 Room 事务内做比较。
 *
 * <p>覆盖 3 个 hook:
 * <ol>
 *   <li>主路径 SUCCEEDED → writeEpisodicOnTerminal 收到 requestEpoch=request.getEpoch()</li>
 *   <li>pre-loop terminal(CANCELLED)→ 同上</li>
 *   <li>protocol-error(NONE finishReason)→ 同上</li>
 * </ol>
 */
public final class AgentEngineEpisodicWriteEpochPropTest {
    private MockCapabilityProvider provider;
    private CapabilityRegistry registry;
    private SessionManager sessionManager;
    private SessionLockManager sessionLockManager;
    private ToolExecutor toolExecutor;
    private ModelCallExecutor modelCallExecutor;

    @Before
    public void setUp() {
        registry = CapabilityRegistry.createDemoRegistry();
        provider = new MockCapabilityProvider(new InMemoryMemoryStore());
        sessionManager = new SessionManager();
        sessionLockManager = new SessionLockManager();
        toolExecutor = new ToolExecutor(4);
        modelCallExecutor = new ModelCallExecutor(2);
    }

    @Test
    public void mainExitPropagatesRequestEpoch() {
        CapturingWriter writer = new CapturingWriter();
        AgentEngine engine = newEngine(new DemoModelGateway(), writer);

        AgentRequest request = AgentRequest.builder("查电量", Actor.DRIVER)
                .sessionId("happy").epoch(7L).build();
        AgentOutcome outcome = engine.execute(request);

        assertEquals(TaskState.SUCCEEDED, outcome.getFinalState());
        assertEquals("writeEpisodicOnTerminal 调 1 次", 1, writer.episodicCount.get());
        assertEquals("requestEpoch 透传正确", 7L, writer.lastRequestEpoch.get());
    }

    @Test
    public void preLoopTerminalPropagatesRequestEpoch() {
        CapturingWriter writer = new CapturingWriter();
        AgentEngine engine = newEngine(new DemoModelGateway(), writer);
        CancellationToken token = new CancellationToken();
        token.cancel();

        AgentRequest request = AgentRequest.builder("查电量", Actor.DRIVER)
                .sessionId("cancel").cancellationToken(token).epoch(9L).build();
        AgentOutcome outcome = engine.execute(request);

        assertEquals(TaskState.CANCELLED, outcome.getFinalState());
        assertEquals("terminalOutcome hook 也调 writeEpisodicOnTerminal",
                1, writer.episodicCount.get());
        assertEquals("requestEpoch 透传正确", 9L, writer.lastRequestEpoch.get());
    }

    @Test
    public void protocolErrorPropagatesRequestEpoch() {
        CapturingWriter writer = new CapturingWriter();
        ModelGateway gateway = req -> ModelTurn.of("oops", FinishReason.NONE);
        AgentEngine engine = newEngine(gateway, writer);

        AgentRequest request = AgentRequest.builder("查电量", Actor.DRIVER)
                .sessionId("perr").epoch(11L).build();
        AgentOutcome outcome = engine.execute(request);

        assertEquals(StopReason.PROTOCOL_ERROR, outcome.getStopReason());
        assertEquals(1, writer.episodicCount.get());
        assertEquals("requestEpoch 透传正确", 11L, writer.lastRequestEpoch.get());
    }

    @Test
    public void defaultEpochZeroIsPropagatedWhenNotSet() {
        // AgentRequest 默认 epoch=0L(未显式注入,旧版兼容路径)
        CapturingWriter writer = new CapturingWriter();
        AgentEngine engine = newEngine(new DemoModelGateway(), writer);

        AgentRequest request = AgentRequest.builder("查电量", Actor.DRIVER)
                .sessionId("default-epoch").build();
        engine.execute(request);

        assertTrue(writer.lastRequestEpoch.get() == 0L);
    }

    private AgentEngine newEngine(ModelGateway gateway, TaskMemoryWriter writer) {
        return new AgentEngine(gateway, modelCallExecutor, new PolicyEngine(registry),
                registry, provider, sessionManager, new DefaultContextUpdater(),
                sessionLockManager, toolExecutor, new AgentBudget(), null,
                new AgentEngineConfiguration.Builder()
                        .taskMemoryWriter(writer)
                        .build());
    }

    private static final class CapturingWriter implements TaskMemoryWriter {
        final AtomicInteger episodicCount = new AtomicInteger();
        final AtomicLong lastRequestEpoch = new AtomicLong(-1L);

        @Override
        public void writeEpisodicOnTerminal(AgentRequest request, AgentOutcome outcome, long requestEpoch) {
            episodicCount.incrementAndGet();
            lastRequestEpoch.set(requestEpoch);
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
