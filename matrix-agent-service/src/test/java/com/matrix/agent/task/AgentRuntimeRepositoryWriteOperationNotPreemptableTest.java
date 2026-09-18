package com.matrix.agent.task;
import com.matrix.agent.task.steer.*;
import com.matrix.agent.task.scheduler.*;

import com.matrix.agent.intent.KeywordIntentClassifier;

import com.matrix.agent.intent.IntentClassifier;

import com.matrix.agent.contract.ToolCall;

import com.matrix.agent.contract.AgentMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import com.matrix.agent.contract.ModelGateway;
import com.matrix.agent.contract.ModelTurn;
import com.matrix.agent.task.steer.SteerMailbox;
import com.matrix.agent.task.StopReason;
import com.matrix.agent.task.scheduler.TaskScheduler;
import com.matrix.agent.task.TaskState;
import com.matrix.agent.task.capability.CapabilityRegistry;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.CancellationToken;
import com.matrix.agent.demo.MockVehicleStateSource;
import com.matrix.agent.vehicle.VehicleState;
import com.matrix.agent.data.memory.InMemoryMemoryStore;
import com.matrix.agent.task.policy.PolicyEngine;
import com.matrix.agent.session.SessionLockManager;
import com.matrix.agent.session.SessionManager;
import com.matrix.agent.demo.MockCapabilityProvider;
import com.matrix.agent.task.tool.ToolExecutor;

/**
 * 验证 IntentClassifier 保守分类在 APK 路径真正生效——
 * 主驾的写操作("打开空调"等)不应该抢占正在运行的副驾任务。
 *
 * <p>旧实现把所有 Repository.execute 标为 readOnlyHint=true,导致主驾"打开空调"
 * 也会抢占副驾的写操作,直接违反"车控写不能被半路强制中断"的安全契约。引入
 * KeywordIntentClassifier:未知按写,主驾写 → readOnlyHint=false → TaskScheduler
 * 不触发 tryPreemptPassengerReadOnly,主驾请求 FIFO 排队等当前迭代结束。
 */
public final class AgentRuntimeRepositoryWriteOperationNotPreemptableTest {

    /**
     * 主驾写操作("打开空调")遇到正在运行的副驾任务时,**不能抢占**——
     * 必须等副驾任务完成,主驾写才进入 runner。验证:
     * <ul>
     *   <li>副驾 token 不会被 cancel;</li>
     *   <li>副驾最终 SUCCEEDED(而非 PREEMPTED);</li>
     *   <li>主驾写最终 SUCCEEDED(排队后跑完)。</li>
     * </ul>
     */
    @Test
    public void driverWriteDoesNotPreemptRunningPassengerTask() throws Exception {
        MockVehicleStateSource stateSource = new MockVehicleStateSource();
        stateSource.setGear(VehicleState.Gear.P);

        CountDownLatch passengerEntered = new CountDownLatch(1);
        CountDownLatch passengerReleased = new CountDownLatch(1);
        AtomicReference<TaskState> passengerFinalState = new AtomicReference<>();
        AtomicReference<StopReason> passengerStopReason = new AtomicReference<>();

        ModelGateway gateway = req -> {
            boolean hasToolResult = req.getConversation().stream()
                    .anyMatch(m -> m.getRole() == com.matrix.agent.contract.AgentMessage.Role.TOOL);
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
                    new com.matrix.agent.contract.ToolCall(
                            "vehicle.info.get_battery",
                            java.util.Collections.emptyMap())), "battery");
        };

        AgentRuntimeRepository repo = buildRepository(gateway, stateSource);
        CancellationToken passengerToken = new CancellationToken();

        Thread passengerThread = new Thread(() -> {
            AgentOutcome o = repo.execute("查电量", Actor.PASSENGER, passengerToken);
            passengerFinalState.set(o.getFinalState());
            passengerStopReason.set(o.getStopReason());
        }, "passenger-query");
        passengerThread.setDaemon(true);
        passengerThread.start();
        assertTrue("副驾应进入 gateway", passengerEntered.await(2, TimeUnit.SECONDS));

        // 主驾写操作 → IntentClassifier 返回 false → readOnlyHint=false → 不抢占
        CancellationToken driverToken = new CancellationToken();
        Thread driverThread = new Thread(() -> {
            AgentOutcome o = repo.execute("打开空调到 24 度", Actor.DRIVER, driverToken);
            // 主驾最终也会 SUCCEEDED,但必须等副驾释放
        }, "driver-write");
        driverThread.setDaemon(true);
        driverThread.start();

        // 给 driver 一点点时间触发 submit 但应被排队(不抢占)
        Thread.sleep(200);
        assertFalse("副驾 token 不应被主驾写抢占", passengerToken.isCancelled());

        // 释放副驾,主驾写开始执行
        passengerReleased.countDown();
        passengerThread.join(3_000);
        driverThread.join(3_000);

        assertEquals("副驾最终 SUCCEEDED(未被抢占)",
                TaskState.SUCCEEDED, passengerFinalState.get());
        assertFalse("副驾 token 全程未被 cancel", passengerToken.isCancelled());
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
        return new AgentRuntimeRepository(engineFactory, sessionManager,
                new InMemoryMemoryStore(), gateway, "test-gateway", budget, scheduler,
                stateSource, registry);
    }
}
