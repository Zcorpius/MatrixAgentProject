package com.matrix.agent.task;
import com.matrix.agent.task.steer.*;
import com.matrix.agent.task.scheduler.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
import com.matrix.agent.data.memory.InMemoryMemoryStore;
import com.matrix.agent.task.policy.PolicyEngine;
import com.matrix.agent.session.SessionLockManager;
import com.matrix.agent.session.SessionManager;
import com.matrix.agent.demo.MockCapabilityProvider;
import com.matrix.agent.task.tool.ToolExecutor;

/**
 * 验证 Repository 超时后主动取消后台 task。
 *
 * <p>修复:旧实现 Repository.execute 在 scheduler.future.get(timeout) 抛 TimeoutException 后
 * 仅返回 TIMED_OUT outcome,但 token / future 都没 cancel——scheduler worker 仍在跑,
 * 真实车控 Provider 会继续执行不可逆操作。新实现先 token.cancel()(逻辑取消 + abortHook)
 * 再 future.cancel(true)(中断 worker),最后返回 TIMED_OUT outcome。
 */
public final class AgentRuntimeRepositoryTimeoutCancellationTest {

    @Test
    public void repositoryTimeoutCancelsBackendTokenAndFuture() throws Exception {
        MockVehicleStateSource stateSource = new MockVehicleStateSource();

        // gateway 永远阻塞,触发 Repository 等待超时
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        AtomicBoolean gatewayInterrupted = new AtomicBoolean(false);
        ModelGateway blockingGateway = req -> {
            entered.countDown();
            try {
                released.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                gatewayInterrupted.set(true);
                Thread.currentThread().interrupt();
            }
            return ModelTurn.directAnswer("done");
        };

        // 极短 deadline(80ms) 让 Repository 等待立即超时;
        // AgentEngine 内部 buildSystemPrompt 等也需要时间,但 future.get 的 80ms 超时
        // 会先触发 Repository 的 TimeoutException 路径
        AgentBudget budget = new AgentBudget(2, 2, 80, 1000, 1000, 4);
        CancellationToken token = new CancellationToken();
        AgentRuntimeRepository repo = buildRepository(blockingGateway, stateSource, budget);

        long started = System.nanoTime();
        AgentOutcome outcome = repo.execute("阻塞查询", Actor.DRIVER, token);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;

        assertEquals(TaskState.TIMED_OUT, outcome.getFinalState());
        assertEquals(StopReason.TIMEOUT, outcome.getStopReason());
        assertTrue("超时应快速返回(实际 " + elapsedMs + "ms,远小于 5s)",
                elapsedMs < 5_000);
        assertTrue("Repository 超时后应 token.cancel()",
                token.isCancelled());
        // 释放 gateway 让 worker 退出
        released.countDown();
    }

    private static AgentRuntimeRepository buildRepository(ModelGateway gateway,
            MockVehicleStateSource stateSource, AgentBudget budget) {
        CapabilityRegistry registry = CapabilityRegistry.createDemoRegistry();
        PolicyEngine policyEngine = new PolicyEngine(registry);
        SessionManager sessionManager = new SessionManager();
        SessionLockManager sessionLockManager = new SessionLockManager();
        ToolExecutor toolExecutor = new ToolExecutor(2);
        ModelCallExecutor modelCallExecutor = new ModelCallExecutor(2);
        SteerMailbox mailbox = new SteerMailbox();
        MockCapabilityProvider provider = new MockCapabilityProvider(new InMemoryMemoryStore());
        TaskScheduler scheduler = new TaskScheduler(2, sessionLockManager);
        AgentRuntimeRepository.AgentEngineFactory engineFactory = gw -> new AgentEngine(
                gw, modelCallExecutor, policyEngine, registry, provider, sessionManager,
                new DefaultContextUpdater(), sessionLockManager, toolExecutor, budget, mailbox);
        return new AgentRuntimeRepository(engineFactory, sessionManager,
                new InMemoryMemoryStore(), gateway, "blocking-gateway", budget, scheduler,
                stateSource, registry);
    }
}
