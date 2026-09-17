package com.matrix.agent.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.List;

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
import com.matrix.agent.task.identity.InputSource;
import com.matrix.agent.task.identity.KeywordIntentClassifier;
import com.matrix.agent.task.identity.MockVehicleStateSource;
import com.matrix.agent.task.identity.VehicleStateSource;
import com.matrix.agent.data.memory.InMemoryMemoryStore;
import com.matrix.agent.task.policy.PolicyEngine;
import com.matrix.agent.data.session.SessionLockManager;
import com.matrix.agent.data.session.SessionManager;
import com.matrix.agent.task.tool.MockCapabilityProvider;
import com.matrix.agent.task.tool.ToolExecutor;
import com.matrix.agent.voice.FinalTranscript;
import com.matrix.agent.voice.VoiceAgentRequest;
import com.matrix.agent.data.audit.AuditRecord;
import com.matrix.agent.data.audit.AuditRepository;

/**
 * 验证语音/文本入口的 {@link AgentRequest} 元数据贯通。
 *
 * <p>用捕获型 {@link AuditRepository} 截获 {@code AgentRuntimeRepository.dispatch} 末尾
 * {@code persist(outcome, request)} 的 {@link AgentRequest},断言:
 * <ul>
 *   <li>语音入口(execute(VoiceAgentRequest))→ inputSource=VOICE / confidenceAvailable=false /
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
        VoiceAgentRequest vreq = new VoiceAgentRequest(
                new FinalTranscript("开空调", "zh-CN", 0f, false), token);

        AgentOutcome outcome = repo.execute(vreq);
        assertNotNull(outcome);
        assertNotNull("audit 应捕获 request", audit.captured);

        AgentRequest r = audit.captured;
        assertEquals("开空调", r.getText());
        assertEquals(InputSource.VOICE, r.getInputSource());
        assertEquals("zh-CN", r.getLanguageTag());
        assertEquals(Actor.DRIVER, r.getActor());
        assertEquals(VoiceAgentRequest.DEFAULT_DRIVER_ZONE, r.getAudioZoneId());
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

    private static AgentRuntimeRepository buildRepository(AuditRepository audit) {
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
                new DefaultContextUpdater(), sessionLockManager, toolExecutor, budget, mailbox);
        return new AgentRuntimeRepository(engineFactory, provider, sessionManager,
                new InMemoryMemoryStore(), gateway, "voice-meta-test", budget, scheduler,
                stateSource, registry, KeywordIntentClassifier.INSTANCE, audit);
    }

    /** 捕获 persist 的 AgentRequest;query 路径空实现(clearByUserZone 用接口 default)。 */
    private static final class CapturingAudit implements AuditRepository {
        AgentRequest captured;

        @Override
        public void persist(AgentOutcome outcome, AgentRequest request) {
            captured = request;
        }

        @Override
        public AuditRecord queryByRequest(String userId, String zone, String requestId) {
            return null;
        }

        @Override
        public List<AuditRecord> queryBySession(String userId, String zone, String sessionId, int limit) {
            return Collections.emptyList();
        }
    }
}
