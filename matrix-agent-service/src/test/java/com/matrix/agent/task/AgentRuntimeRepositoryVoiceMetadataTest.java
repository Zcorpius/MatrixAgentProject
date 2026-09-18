package com.matrix.agent.task;
import com.matrix.agent.task.steer.*;
import com.matrix.agent.task.scheduler.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.matrix.agent.task.AgentBudget;
import com.matrix.agent.task.AgentEngine;
import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.task.DefaultContextUpdater;
import com.matrix.agent.task.ModelCallExecutor;
import com.matrix.agent.contract.ModelGateway;
import com.matrix.agent.contract.ModelTurn;
import com.matrix.agent.task.steer.SteerMailbox;
import com.matrix.agent.task.scheduler.TaskScheduler;
import com.matrix.agent.task.capability.CapabilityRegistry;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.identity.CancellationToken;
import com.matrix.agent.identity.InputSource;
import com.matrix.agent.intent.KeywordIntentClassifier;
import com.matrix.agent.demo.MockVehicleStateSource;
import com.matrix.agent.vehicle.VehicleStateSource;
import com.matrix.agent.data.memory.InMemoryMemoryStore;
import com.matrix.agent.task.policy.PolicyEngine;
import com.matrix.agent.session.SessionLockManager;
import com.matrix.agent.session.SessionManager;
import com.matrix.agent.demo.MockCapabilityProvider;
import com.matrix.agent.task.tool.ToolExecutor;
import com.matrix.agent.data.audit.NoopAuditRepository;
import com.matrix.agent.task.port.TaskAuditSink;

/**
 * 验证语音/文本入口的 {@link AgentRequest} 元数据贯通。
 *
 * <p>用捕获型 {@link TaskAuditSink} 截获任务边界的 {@link AgentRequest},断言:
 * <ul>
 *   <li>Host-adapted voice invocation → inputSource=VOICE / confidenceAvailable=false /
 *       confidence=0 / 语言与音区与输入一致;</li>
 *   <li>文本入口(execute(String,Actor,token))→ 仍 TOUCH / confidenceAvailable=true /
 *       asrConfidence=1.0(历史默认,守恒不退绿)。</li>
 * </ul>
 *
 * <p>AgentEngine 装配复用 {@link AgentRuntimeRepositoryTimeoutCancellationTest} 的模板;
 * gateway 用 {@link ModelTurn#directAnswer} 立即返回,跑一轮无 tool_call 即 terminal。
 */
public final class AgentRuntimeRepositoryVoiceMetadataTest {

    @Test
    public void voiceRequest_metadataFlowsToAgentRequest() {
        CapturingAudit audit = new CapturingAudit();
        AgentRuntimeRepository repo = buildRepository(audit);

        CancellationToken token = new CancellationToken();
        AgentInvocation vreq = new AgentInvocation("开空调", Actor.DRIVER, 0,
                InputSource.VOICE, "zh-CN", 0f, false, token);

        AgentOutcome outcome = repo.execute(vreq);
        assertNotNull(outcome);
        assertNotNull("audit 应捕获 request", audit.captured);

        AgentRequest r = audit.captured;
        assertEquals("开空调", r.getText());
        assertEquals(InputSource.VOICE, r.getInputSource());
        assertEquals("zh-CN", r.getLanguageTag());
        assertEquals(Actor.DRIVER, r.getActor());
        assertEquals(0, r.getAudioZoneId());
        // 核心:未知置信度绝不伪装成 1.0
        assertFalse(r.isConfidenceAvailable());
        assertEquals(0f, r.getAsrConfidence(), 0f);
    }

    @Test
    public void textRequest_staysTouch() {
        CapturingAudit audit = new CapturingAudit();
        AgentRuntimeRepository repo = buildRepository(audit);

        AgentOutcome outcome = repo.execute("查天气", Actor.DRIVER, new CancellationToken());
        assertNotNull(outcome);
        assertNotNull(audit.captured);

        AgentRequest r = audit.captured;
        // 文本入口守恒:仍 TOUCH,confidenceAvailable 历史默认 true,asrConfidence 默认 1.0
        assertEquals(InputSource.TOUCH, r.getInputSource());
        assertTrue(r.isConfidenceAvailable());
        assertEquals(1.0f, r.getAsrConfidence(), 0f);
        assertEquals("zh-CN", r.getLanguageTag());
    }

    private static AgentRuntimeRepository buildRepository(TaskAuditSink auditSink) {
        CapabilityRegistry registry = CapabilityRegistry.createDemoRegistry();
        PolicyEngine policyEngine = new PolicyEngine(registry);
        SessionManager sessionManager = new SessionManager();
        SessionLockManager sessionLockManager = new SessionLockManager();
        ToolExecutor toolExecutor = new ToolExecutor(2);
        ModelCallExecutor modelCallExecutor = new ModelCallExecutor(2);
        SteerMailbox mailbox = new SteerMailbox();
        MockCapabilityProvider provider = new MockCapabilityProvider(new InMemoryMemoryStore());
        TaskScheduler scheduler = new TaskScheduler(2, sessionLockManager);
        AgentBudget budget = new AgentBudget();
        // gateway 立即直接回答,AgentEngine 跑一轮无 tool_call 即 terminal
        ModelGateway gateway = req -> ModelTurn.directAnswer("done");
        VehicleStateSource stateSource = new MockVehicleStateSource();
        AgentRuntimeRepository.AgentEngineFactory engineFactory = gw -> new AgentEngine(
                gw, modelCallExecutor, policyEngine, registry, provider, sessionManager,
                new DefaultContextUpdater(), sessionLockManager, toolExecutor, budget, mailbox,
                new AgentEngineConfiguration.Builder().auditSink(auditSink).build());
        return new AgentRuntimeRepository(engineFactory, sessionManager,
                new InMemoryMemoryStore(), gateway, "voice-meta-test", budget, scheduler,
                stateSource, registry, KeywordIntentClassifier.INSTANCE, NoopAuditRepository.INSTANCE);
    }

    /** 捕获任务层 audit 的 AgentRequest，避免让数据层承担任务模型。 */
    private static final class CapturingAudit implements TaskAuditSink {
        AgentRequest captured;

        @Override
        public void persist(AgentOutcome outcome, AgentRequest request) {
            captured = request;
        }

    }
}
