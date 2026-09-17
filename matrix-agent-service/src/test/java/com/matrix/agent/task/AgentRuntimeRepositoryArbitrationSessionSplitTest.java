package com.matrix.agent.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.matrix.agent.task.AgentBudget;
import com.matrix.agent.task.AgentEngine;
import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.task.DefaultContextUpdater;
import com.matrix.agent.task.ModelCallExecutor;
import com.matrix.agent.task.ModelGateway;
import com.matrix.agent.task.ModelTurn;
import com.matrix.agent.task.SteerMailbox;
import com.matrix.agent.task.TaskScheduler;
import com.matrix.agent.task.capability.CapabilityRegistry;
import com.matrix.agent.task.identity.Actor;
import com.matrix.agent.task.identity.AgentRequest;
import com.matrix.agent.task.identity.CancellationToken;
import com.matrix.agent.task.identity.MockVehicleStateSource;
import com.matrix.agent.task.identity.VehicleState;
import com.matrix.agent.data.memory.InMemoryMemoryStore;
import com.matrix.agent.task.policy.PolicyEngine;
import com.matrix.agent.data.session.SessionLockManager;
import com.matrix.agent.data.session.SessionManager;
import com.matrix.agent.task.tool.MockCapabilityProvider;
import com.matrix.agent.task.tool.ToolExecutor;

/**
 * 验证 Repository 把 arbitrationKey(同车调度仲裁)
 * 和 sessionId(乘员上下文隔离)拆成两个独立的 key。
 *
 * <p>旧实现把主驾和副驾 sessionId 都设成 demo-vehicle 让 TaskScheduler
 * 抢占可触达,但 SessionManager 和 SteerMailbox 也跟着共享——副驾的 REPROMPT
 * 可能进入主驾 mailbox,后续上下文持久化时也会主副驾串扰。
 *
 * <p>拆键:TaskScheduler 内部用 arbitrationKey(demo-vehicle),AgentEngine
 * 内部(SessionManager / SteerMailbox)用 sessionId(demo-driver / demo-passenger)。
 * 即使共享 arbitrationKey 让抢占生效,乘员上下文仍按 sessionId 严格隔离。
 */
public final class AgentRuntimeRepositoryArbitrationSessionSplitTest {

    @Test
    public void driverRequestHasDistinctArbitrationAndSessionKeys() {
        AtomicReference<AgentRequest> captured = new AtomicReference<>();
        ModelGateway gateway = req -> {
            captured.set(req.getAgentRequest());
            return ModelTurn.directAnswer("done");
        };
        AgentRuntimeRepository repo = buildRepository(gateway);

        repo.execute("查胎压", Actor.DRIVER, new CancellationToken());

        AgentRequest req = captured.get();
        assertEquals("arbitrationKey 应是同车仲裁键",
                "demo-vehicle", req.getArbitrationKey());
        assertEquals("sessionId 应按乘员隔离",
                "demo-driver", req.getSessionId());
        assertNotEquals("arbitrationKey 和 sessionId 必须不同(否则 Round 2 串扰 bug 复现)",
                req.getArbitrationKey(), req.getSessionId());
    }

    @Test
    public void passengerRequestHasDistinctArbitrationAndSessionKeys() {
        AtomicReference<AgentRequest> captured = new AtomicReference<>();
        ModelGateway gateway = req -> {
            captured.set(req.getAgentRequest());
            return ModelTurn.directAnswer("done");
        };
        AgentRuntimeRepository repo = buildRepository(gateway);

        repo.execute("查电量", Actor.PASSENGER, new CancellationToken());

        AgentRequest req = captured.get();
        assertEquals("arbitrationKey 应是同车仲裁键(主副驾共享)",
                "demo-vehicle", req.getArbitrationKey());
        assertEquals("sessionId 应按乘员隔离",
                "demo-passenger", req.getSessionId());
        assertNotEquals("arbitrationKey 和 sessionId 必须不同",
                req.getArbitrationKey(), req.getSessionId());
    }

    @Test
    public void driverAndPassengerShareArbitrationKeyButIsolateSession() {
        AtomicReference<AgentRequest> driverCapture = new AtomicReference<>();
        AtomicReference<AgentRequest> passengerCapture = new AtomicReference<>();
        ModelGateway gateway = req -> {
            if (req.getAgentRequest().getActor() == Actor.DRIVER) {
                driverCapture.set(req.getAgentRequest());
            } else {
                passengerCapture.set(req.getAgentRequest());
            }
            return ModelTurn.directAnswer("done");
        };
        AgentRuntimeRepository repo = buildRepository(gateway);

        repo.execute("查胎压", Actor.DRIVER, new CancellationToken());
        repo.execute("查电量", Actor.PASSENGER, new CancellationToken());

        // 抢占前提:同 arbitrationKey
        assertEquals("主副驾 arbitrationKey 必须相同(让 TaskScheduler 抢占可触达)",
                driverCapture.get().getArbitrationKey(),
                passengerCapture.get().getArbitrationKey());
        // 隔离前提:不同 sessionId
        assertNotEquals("主副驾 sessionId 必须不同(SteerMailbox / SessionManager 隔离)",
                driverCapture.get().getSessionId(),
                passengerCapture.get().getSessionId());
    }

    private static AgentRuntimeRepository buildRepository(ModelGateway gateway) {
        CapabilityRegistry registry = CapabilityRegistry.createDemoRegistry();
        PolicyEngine policyEngine = new PolicyEngine(registry);
        SessionManager sessionManager = new SessionManager();
        SessionLockManager sessionLockManager = new SessionLockManager();
        ToolExecutor toolExecutor = new ToolExecutor(2);
        ModelCallExecutor modelCallExecutor = new ModelCallExecutor(2);
        SteerMailbox mailbox = new SteerMailbox();
        AgentBudget budget = new AgentBudget();
        MockCapabilityProvider provider = new MockCapabilityProvider(new InMemoryMemoryStore());
        MockVehicleStateSource stateSource = new MockVehicleStateSource();
        stateSource.setGear(VehicleState.Gear.P);
        TaskScheduler scheduler = new TaskScheduler(2, sessionLockManager);
        AgentRuntimeRepository.AgentEngineFactory engineFactory = gw -> new AgentEngine(
                gw, modelCallExecutor, policyEngine, registry, provider, sessionManager,
                new DefaultContextUpdater(), sessionLockManager, toolExecutor, budget, mailbox);
        AgentRuntimeRepository repo = new AgentRuntimeRepository(engineFactory, provider,
                sessionManager, new InMemoryMemoryStore(), gateway, "test-gateway", budget,
                scheduler, stateSource, registry);
        return repo;
    }
}
