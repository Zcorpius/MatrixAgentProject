package com.matrix.agent.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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
import com.matrix.agent.task.identity.KeywordMemoryIntentDetector;
import com.matrix.agent.task.identity.MemoryIntentDetector;
import com.matrix.agent.task.identity.MockVehicleStateSource;
import com.matrix.agent.data.memory.InMemoryMemoryStore;
import com.matrix.agent.task.policy.PolicyEngine;
import com.matrix.agent.data.session.SessionLockManager;
import com.matrix.agent.data.session.SessionManager;
import com.matrix.agent.task.tool.MockCapabilityProvider;
import com.matrix.agent.task.tool.ToolExecutor;

/**
 * Repository.execute 入口注入 MemoryIntentDetector,
 * 构造的 AgentRequest.isMemorySaveAllowed() 与 detector 返回值一致。
 *
 * <p>用户硬约束——"长期记忆仅由用户决定"。Repository 在 build AgentRequest 前
 * 调 memoryIntentDetector.isExplicitMemorySave(command),把结果作为 memorySaveAllowed
 * 注入。memory.semantic.save handler 在 false 时直接 POLICY_REJECTED(硬 gate)。
 *
 * <p>验证:
 * <ul>
 *   <li>装配 KeywordMemoryIntentDetector + command 含"记住" → request.memorySaveAllowed=true</li>
 *   <li>装配 KeywordMemoryIntentDetector + 纯查询 command → request.memorySaveAllowed=false</li>
 *   <li>未装配 detector(默认 NOOP)→ 任何 command 都 false(保守)</li>
 *   <li>setMemoryIntentDetector(null) 退化为 NOOP</li>
 * </ul>
 */
public final class AgentRuntimeRepositoryMemorySaveFlagTest {

    @Test
    public void keywordDetectorFlagsExplicitMemorySaveCommand() {
        AtomicReference<AgentRequest> captured = new AtomicReference<>();
        AgentRuntimeRepository repo = buildRepository(capturingGateway(captured));
        repo.setMemoryIntentDetector(KeywordMemoryIntentDetector.INSTANCE);

        repo.execute("记住我对花生过敏", Actor.DRIVER, new CancellationToken());

        assertNotNull(captured.get());
        assertTrue("含'记住' → memorySaveAllowed=true",
                captured.get().isMemorySaveAllowed());
    }

    @Test
    public void keywordDetectorRejectsPureQueryCommand() {
        AtomicReference<AgentRequest> captured = new AtomicReference<>();
        AgentRuntimeRepository repo = buildRepository(capturingGateway(captured));
        repo.setMemoryIntentDetector(KeywordMemoryIntentDetector.INSTANCE);

        repo.execute("今天天气怎么样", Actor.DRIVER, new CancellationToken());

        assertNotNull(captured.get());
        assertFalse("纯查询 → memorySaveAllowed=false",
                captured.get().isMemorySaveAllowed());
    }

    @Test
    public void noopDetectorDefaultsAllRequestsToFalse() {
        AtomicReference<AgentRequest> captured = new AtomicReference<>();
        AgentRuntimeRepository repo = buildRepository(capturingGateway(captured));
        // 不调 setMemoryIntentDetector —— 默认 NOOP

        repo.execute("记住我对花生过敏", Actor.DRIVER, new CancellationToken());

        assertNotNull(captured.get());
        assertFalse("默认 NOOP detector → 即便命中关键词也 false(保守)",
                captured.get().isMemorySaveAllowed());
    }

    @Test
    public void setNullDetectorRevertsToNoop() {
        AtomicReference<AgentRequest> captured = new AtomicReference<>();
        AgentRuntimeRepository repo = buildRepository(capturingGateway(captured));
        repo.setMemoryIntentDetector(KeywordMemoryIntentDetector.INSTANCE);
        repo.setMemoryIntentDetector(null);  // null → NOOP 防御

        repo.execute("记住我对花生过敏", Actor.DRIVER, new CancellationToken());

        assertNotNull(captured.get());
        assertEquals("null setter 退化为 NOOP,memorySaveAllowed=false",
                false, captured.get().isMemorySaveAllowed());
    }

    @Test
    public void customDetectorDecisionPropagatedToRequest() {
        AtomicReference<AgentRequest> captured = new AtomicReference<>();
        AgentRuntimeRepository repo = buildRepository(capturingGateway(captured));
        // 自定义 detector:command 以"X"开头时返回 true(模拟 LLM-based detector,后续版本升级路径)
        repo.setMemoryIntentDetector(command -> command != null && command.startsWith("X"));

        repo.execute("X-save-this", Actor.DRIVER, new CancellationToken());
        assertTrue("自定义 detector 命中 → true", captured.get().isMemorySaveAllowed());

        repo.execute("普通查询", Actor.DRIVER, new CancellationToken());
        assertFalse("自定义 detector 未命中 → false", captured.get().isMemorySaveAllowed());
    }

    private static ModelGateway capturingGateway(AtomicReference<AgentRequest> captured) {
        return req -> {
            captured.set(req.getAgentRequest());
            return ModelTurn.directAnswer("ok");
        };
    }

    private static AgentRuntimeRepository buildRepository(ModelGateway gateway) {
        MockVehicleStateSource stateSource = new MockVehicleStateSource();
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
                new InMemoryMemoryStore(), gateway, "capturing-gateway", budget, scheduler,
                stateSource, registry);
    }

    /** 防御性引用,避免编译器警告"unused"。 */
    @SuppressWarnings("unused")
    private static final MemoryIntentDetector UNUSED = MemoryIntentDetector.NOOP;
}
