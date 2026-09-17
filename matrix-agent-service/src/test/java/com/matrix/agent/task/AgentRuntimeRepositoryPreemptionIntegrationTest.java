package com.matrix.agent.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import com.matrix.agent.task.StopReason;
import com.matrix.agent.task.TaskScheduler;
import com.matrix.agent.task.TaskState;
import com.matrix.agent.task.capability.CapabilityRegistry;
import com.matrix.agent.task.identity.Actor;
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
 * 端到端:Repository.execute 把主驾和副驾共享同一 session 队列,
 * 让 TaskScheduler 的主驾优先抢占在 APK 路径真正可触达。旧实现
 * 按 actor 隔离 sessionId(demo-driver / demo-passenger),主驾请求永远进不到副驾队列,
 * 抢占分支永不进入。
 *
 * <p>同时验证:Scheduler 内部把 token-cancel 导致的 CANCELLED 重映射为 PREEMPTED,
 * Repository 拿到的副驾 outcome 是 StopReason.PREEMPTED + TaskState.PREEMPTED
 * (旧实现下永远是 CANCELLED+CANCELLED)。
 */
public final class AgentRuntimeRepositoryPreemptionIntegrationTest {

    @Test
    public void driverRequestPreemptsRunningPassengerQueryInApkPath() throws Exception {
        MockVehicleStateSource stateSource = new MockVehicleStateSource();
        stateSource.setGear(VehicleState.Gear.P);

        CountDownLatch passengerEntered = new CountDownLatch(1);
        CountDownLatch passengerReleased = new CountDownLatch(1);
        AtomicReference<TaskState> passengerFinalState = new AtomicReference<>();
        AtomicReference<StopReason> passengerStopReason = new AtomicReference<>();

        // gateway 第 1 轮(无 tool_result)→ 返回 tool_call;第 2 轮(有 tool_result)→ directAnswer
        // 但 passenger 在第 1 轮 gateway 调用中阻塞,模拟"副驾查询进行中"
        // driver 抢占后 passenger token 被 cancel,passenger 出 cancellationState 路径
        ModelGateway gateway = req -> {
            boolean hasToolResult = req.getConversation().stream()
                    .anyMatch(m -> m.getRole() == com.matrix.agent.task.AgentMessage.Role.TOOL);
            if (req.getAgentRequest().getActor() == Actor.PASSENGER && !hasToolResult) {
                passengerEntered.countDown();
                try {
                    passengerReleased.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            if (hasToolResult) {
                return ModelTurn.directAnswer("done");
            }
            return ModelTurn.ofToolCalls(java.util.Collections.singletonList(
                    new com.matrix.agent.task.tool.ToolCall(
                            "vehicle.info.get_battery", java.util.Collections.emptyMap())), "battery");
        };

        AgentRuntimeRepository repo = buildRepository(gateway, stateSource);
        CancellationToken passengerToken = new CancellationToken();

        // 副驾先提交(在另一个线程,因为会阻塞)
        Thread passengerThread = new Thread(() -> {
            AgentOutcome o = repo.execute("查电量", Actor.PASSENGER, passengerToken);
            passengerFinalState.set(o.getFinalState());
            passengerStopReason.set(o.getStopReason());
        }, "passenger-request");
        passengerThread.setDaemon(true);
        passengerThread.start();
        assertTrue("副驾应进入 gateway", passengerEntered.await(2, TimeUnit.SECONDS));

        // 主驾提交 → 应触发抢占
        CancellationToken driverToken = new CancellationToken();
        AgentOutcome driverOutcome = repo.execute("查胎压", Actor.DRIVER, driverToken);

        passengerReleased.countDown();
        passengerThread.join(3_000);

        assertEquals("主驾应 SUCCEEDED", TaskState.SUCCEEDED, driverOutcome.getFinalState());
        assertTrue("副驾 token 应被 cancel", passengerToken.isCancelled());
        assertEquals("副驾终态应是 PREEMPTED(Scheduler 重映射)",
                TaskState.PREEMPTED, passengerFinalState.get());
        assertEquals("副驾 StopReason 应是 PREEMPTED(Scheduler 重映射)",
                StopReason.PREEMPTED, passengerStopReason.get());
    }

    private static AgentRuntimeRepository buildRepository(ModelGateway gateway,
            MockVehicleStateSource stateSource) {
        CapabilityRegistry registry = CapabilityRegistry.createDemoRegistry();
        PolicyEngine policyEngine = new PolicyEngine(registry);
        SessionManager sessionManager = new SessionManager();
        SessionLockManager sessionLockManager = new SessionLockManager();
        ToolExecutor toolExecutor = new ToolExecutor(2);
        ModelCallExecutor modelCallExecutor = new ModelCallExecutor(2);
        SteerMailbox mailbox = new SteerMailbox();
        AgentBudget budget = new AgentBudget();
        MockCapabilityProvider provider = new MockCapabilityProvider(new InMemoryMemoryStore());
        TaskScheduler scheduler = new TaskScheduler(2, sessionLockManager);
        AgentRuntimeRepository.AgentEngineFactory engineFactory = gw -> new AgentEngine(
                gw, modelCallExecutor, policyEngine, registry, provider, sessionManager,
                new DefaultContextUpdater(), sessionLockManager, toolExecutor, budget, mailbox);
        return new AgentRuntimeRepository(engineFactory, provider, sessionManager,
                new InMemoryMemoryStore(), gateway, "test-gateway", budget, scheduler,
                stateSource, registry);
    }
}
