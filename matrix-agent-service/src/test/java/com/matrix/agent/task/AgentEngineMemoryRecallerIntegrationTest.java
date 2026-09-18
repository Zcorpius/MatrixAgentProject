package com.matrix.agent.task;

import com.matrix.agent.contract.ModelTurn;

import com.matrix.agent.contract.ModelTurnRequest;

import com.matrix.agent.contract.ModelGateway;

import com.matrix.agent.task.capability.CapabilityRegistry;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.identity.VehicleZone;
import com.matrix.agent.data.memory.InMemoryMemoryStore;
import com.matrix.agent.data.memory.MemoryLayer;
import com.matrix.agent.data.memory.MemoryRecaller;
import com.matrix.agent.data.memory.MemoryScope;
import com.matrix.agent.data.memory.MemorySnippet;
import com.matrix.agent.task.policy.PolicyEngine;
import com.matrix.agent.task.prompt.PromptContextAssembler;
import com.matrix.agent.session.SessionLockManager;
import com.matrix.agent.session.SessionManager;
import com.matrix.agent.demo.MockCapabilityProvider;
import com.matrix.agent.task.tool.ToolExecutor;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * AgentEngine.buildSystemPrompt 接入 MemoryRecaller 的契约测试。
 *
 * <p>用 fake ModelGateway 捕获 ModelTurnRequest.getSystemPrompt(),
 * 断言:
 * <ul>
 *   <li>recaller=null 时退化为旧版文案(不含"已召回的 Memory"段);</li>
 *   <li>recaller != null 且返回非空 snippet 时,systemPrompt 含 "[layer] key" 列表;</li>
 *   <li>recaller != null 但返回空时,systemPrompt 与旧版文案等价。</li>
 * </ul>
 *
 * <p>不验证 value 是否被拼——buildSystemPrompt 故意只拼 key(避免 prompt injection)。
 */
public final class AgentEngineMemoryRecallerIntegrationTest {
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

    /** recaller=null:systemPrompt 必须是旧版文案(不含"已召回的 Memory")。 */
    @Test
    public void nullRecallerKeepsV043PromptShape() {
        PromptCapturingGateway gateway = new PromptCapturingGateway();
        AgentEngine engine = newEngine(gateway, null);

        AgentOutcome outcome = engine.execute(AgentRequest.builder("查电量", Actor.DRIVER)
                .sessionId("null-recaller").build());

        assertEquals(TaskState.SUCCEEDED, outcome.getFinalState());
        assertNotNull(gateway.capturedSystemPrompt);
        assertTrue("V0.4.3 文案应含\"你是车机 AI 助理\"",
                gateway.capturedSystemPrompt.contains("你是车机 AI 助理"));
        assertTrue("recaller=null 不应出现召回段落",
                !gateway.capturedSystemPrompt.contains("已召回的 Memory"));
    }

    /** recaller 返回非空:systemPrompt 末尾必须含每个 snippet 的 [layer] key 列表。 */
    @Test
    public void recallerAppendsSnippetKeysToSystemPrompt() {
        PromptCapturingGateway gateway = new PromptCapturingGateway();
        MemoryScope scope = new MemoryScope("demo-driver", VehicleZone.DRIVER);
        List<MemorySnippet> snippets = Arrays.asList(
                MemorySnippet.of(MemoryLayer.PREFERENCE, scope, "user.preference.temperature", "24"),
                MemorySnippet.of(MemoryLayer.EPISODIC, scope, "session.last_navigation", "公司"),
                MemorySnippet.of(MemoryLayer.WORKING, scope, "turn.last_query", "电量"));
        AgentEngine engine = newEngine(gateway, (s, sid, text, max) -> snippets);

        AgentOutcome outcome = engine.execute(AgentRequest.builder("查电量", Actor.DRIVER)
                .sessionId("with-recaller").build());

        assertEquals(TaskState.SUCCEEDED, outcome.getFinalState());
        assertNotNull(gateway.capturedSystemPrompt);
        assertTrue("systemPrompt 必须含召回段落标题",
                gateway.capturedSystemPrompt.contains("已召回的 Memory"));
        assertTrue("systemPrompt 必须含 PREFERENCE snippet key",
                gateway.capturedSystemPrompt.contains("[preference] user.preference.temperature"));
        assertTrue("systemPrompt 必须含 EPISODIC snippet key",
                gateway.capturedSystemPrompt.contains("[episodic] session.last_navigation"));
        assertTrue("systemPrompt 必须含 WORKING snippet key",
                gateway.capturedSystemPrompt.contains("[working] turn.last_query"));
    }

    /**
     * recaller != null 但返回空:systemPrompt 与旧版等价(不附加空段)。
     * 这是召回失败的兜底——不会把"已召回的 Memory:"空标题拼到 prompt。
     */
    @Test
    public void recallerReturningEmptyKeepsV043Prompt() {
        PromptCapturingGateway gateway = new PromptCapturingGateway();
        AgentEngine engine = newEngine(gateway, (s, sid, text, max) -> Collections.emptyList());

        AgentOutcome outcome = engine.execute(AgentRequest.builder("查电量", Actor.DRIVER)
                .sessionId("empty-recaller").build());

        assertEquals(TaskState.SUCCEEDED, outcome.getFinalState());
        assertNotNull(gateway.capturedSystemPrompt);
        assertTrue("空召回列表不应附加段落标题",
                !gateway.capturedSystemPrompt.contains("已召回的 Memory"));
        assertTrue("仍保留 V0.4.3 基础文案",
                gateway.capturedSystemPrompt.contains("你是车机 AI 助理"));
    }

    /**
     * 修复:recaller 抛异常必须降级到 base prompt,
     * 不能让记忆故障崩溃车机任务入口。AgentEngine.execute 返回 SUCCEEDED,
     * systemPrompt 是旧版基础文案(不含"已召回的 Memory"段)。
     */
    @Test
    public void recallerThrowingDegradesToBasePrompt() {
        PromptCapturingGateway gateway = new PromptCapturingGateway();
        MemoryRecaller failing = (s, sid, text, max) -> {
            throw new RuntimeException("recall backend down");
        };
        AgentEngine engine = newEngine(gateway, failing);

        AgentOutcome outcome = engine.execute(AgentRequest.builder("查电量", Actor.DRIVER)
                .sessionId("failing-recaller").build());

        // 主路径未崩溃 + 模型正常收到 prompt + 任务 SUCCEEDED
        assertEquals(TaskState.SUCCEEDED, outcome.getFinalState());
        assertNotNull(gateway.capturedSystemPrompt);
        assertTrue("异常降级后仍含 V0.4.3 基础文案",
                gateway.capturedSystemPrompt.contains("你是车机 AI 助理"));
        assertTrue("异常降级后不应附加召回段落",
                !gateway.capturedSystemPrompt.contains("已召回的 Memory"));
    }

    /** recaller 返回 null(防御性):等价于 empty,降级到 base prompt。 */
    @Test
    public void recallerReturningNullDegradesToBasePrompt() {
        PromptCapturingGateway gateway = new PromptCapturingGateway();
        MemoryRecaller nullReturning = (s, sid, text, max) -> null;
        AgentEngine engine = newEngine(gateway, nullReturning);

        AgentOutcome outcome = engine.execute(AgentRequest.builder("查电量", Actor.DRIVER)
                .sessionId("null-returning").build());

        assertEquals(TaskState.SUCCEEDED, outcome.getFinalState());
        assertNotNull(gateway.capturedSystemPrompt);
        assertTrue("null 返回不应崩溃,降级到 base prompt",
                gateway.capturedSystemPrompt.contains("你是车机 AI 助理"));
        assertTrue(!gateway.capturedSystemPrompt.contains("已召回的 Memory"));
    }

    private AgentEngine newEngine(ModelGateway gateway, MemoryRecaller recaller) {
        return new AgentEngine(gateway, modelCallExecutor, new PolicyEngine(registry),
                registry, provider, sessionManager, new DefaultContextUpdater(),
                sessionLockManager, toolExecutor, new AgentBudget(), null,
                new AgentEngineConfiguration.Builder()
                        .promptContextAssembler(new PromptContextAssembler(recaller, null))
                        .build());
    }

    /** 简易 fake gateway:每次 decide 把 systemPrompt 抓下来,directAnswer 完成 loop。 */
    private static final class PromptCapturingGateway implements ModelGateway {
        volatile String capturedSystemPrompt;

        @Override
        public ModelTurn decide(ModelTurnRequest request) {
            capturedSystemPrompt = request.getSystemPrompt();
            return ModelTurn.directAnswer("done");
        }
    }
}
