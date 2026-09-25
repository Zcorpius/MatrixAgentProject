package com.matrix.agent.model;
import com.matrix.agent.task.redact.*;

import com.matrix.agent.contract.ModelConfig;

import com.matrix.agent.contract.LlmClient;

import com.matrix.agent.contract.ApiProtocol;

import com.matrix.agent.identity.CancellationToken;

import com.matrix.agent.task.capability.CapabilityRegistry;
import com.matrix.agent.contract.ToolDefinition;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.identity.VehicleZone;
import com.matrix.agent.data.memory.InMemoryMemoryStore;
import com.matrix.agent.data.memory.MemoryLayer;
import com.matrix.agent.data.memory.MemoryRecaller;
import com.matrix.agent.data.memory.MemoryScope;
import com.matrix.agent.data.memory.MemorySnippet;
import com.matrix.agent.session.SessionContext;
import com.matrix.agent.session.SessionManager;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * LlmPlanner.savedKeysFor 接入 MemoryRecaller 的契约测试。
 *
 * <p>注入 fake {@link LlmClient}(捕获 userPrompt)+ MemoryRouter(包装 InMemoryMemoryStore),
 * 断言:
 * <ul>
 *   <li>memoryStore 有偏好 key 时,userPrompt 含 "查询时必须使用这些精确字符串之一" 段(旧版兼容);</li>
 *   <li>memoryRecaller 召回非空时,userPrompt 含 "[layer] key" 列表(非 PREFERENCE 层);</li>
 *   <li>recaller=null 时退化为旧版行为(仅 preference key 列表)。</li>
 * </ul>
 *
 * <p>LlmPlanner 的 userPrompt 包含用户原话文本,验证仅检查结构化段标题——
 * 不验证用户原话(SafeLog 治理范围内,不进 fixture)。
 */
public final class LlmPlannerMemoryIntegrationTest {
    private InMemoryMemoryStore memoryStore;
    private CapabilityRegistry registry;
    private SessionManager sessionManager;

    @Before
    public void setUp() {
        memoryStore = new InMemoryMemoryStore();
        registry = CapabilityRegistry.createDemoRegistry();
        sessionManager = new SessionManager();
    }

    /** recaller=null:maintain 旧版行为——仅 preference key 列表。 */
    @Test
    public void nullRecallerKeepsV043Behavior() {
        memoryStore.putPreferenceChecked(driverScope(), "preferred_temperature", "24", memoryStore.currentEpoch());
        CapturingLlmClient client = new CapturingLlmClient();
        LlmPlanner planner = newPlanner(client, null);

        planner.decide(buildDriverRequest(), sessionContext(), driverTools());

        assertNotNull(client.capturedUserPrompt);
        assertTrue("V0.4.3 行为必须含偏好 key",
                client.capturedUserPrompt.contains("preferred_temperature"));
        assertTrue("V0.4.3 行为必须含精确字符串提示",
                client.capturedUserPrompt.contains("查询时必须使用这些精确字符串之一"));
        assertFalse("recaller=null 不应附加召回段",
                client.capturedUserPrompt.contains("其他已召回的 Memory"));
    }

    /** recaller 返回非 PREFERENCE snippet:userPrompt 末尾必须含 [layer] key 列表。 */
    @Test
    public void recallerAppendsNonPreferenceSnippets() {
        memoryStore.putPreferenceChecked(driverScope(), "preferred_temperature", "24", memoryStore.currentEpoch());
        MemoryScope scope = new MemoryScope("demo-driver", VehicleZone.DRIVER);
        // 用 stub recaller 直接返回非 PREFERENCE snippet(模拟 Episodic/Semantic 召回)
        MemoryRecaller stub = (s, sid, text, max) -> {
            assertEquals("planner-memory", sid);
            assertEquals("把主驾温度调到我喜欢", text);
            return Arrays.asList(
                MemorySnippet.of(MemoryLayer.EPISODIC, scope, "session.last_cmd", "导航回家"),
                MemorySnippet.of(MemoryLayer.WORKING, scope, "last_climate_temperature", "driver:24"));
        };

        CapturingLlmClient client = new CapturingLlmClient();
        LlmPlanner planner = newPlanner(client, stub);

        planner.decide(buildDriverRequest(), sessionContext(), driverTools());

        assertNotNull(client.capturedUserPrompt);
        assertTrue("必须含偏好 key V0.4.3 段",
                client.capturedUserPrompt.contains("preferred_temperature"));
        assertTrue("必须含召回段落标题",
                client.capturedUserPrompt.contains("其他已召回的 Memory"));
        assertTrue("必须含 EPISODIC snippet key",
                client.capturedUserPrompt.contains("[episodic] session.last_cmd"));
        assertTrue("必须含 WORKING snippet key",
                client.capturedUserPrompt.contains("[working] last_climate_temperature: 主驾 24°C"));
    }

    /** recaller 返回的 PREFERENCE snippet 不重复出现(preferenceKeysFor 已包含)。 */
    @Test
    public void recallerPreferenceLayerIsSkippedToAvoidDuplication() {
        memoryStore.putPreferenceChecked(driverScope(), "preferred_temperature", "24", memoryStore.currentEpoch());
        MemoryScope scope = new MemoryScope("demo-driver", VehicleZone.DRIVER);
        MemoryRecaller stub = (s, sid, text, max) -> Arrays.asList(
                MemorySnippet.of(MemoryLayer.PREFERENCE, scope, "preferred_temperature", "24"),
                MemorySnippet.of(MemoryLayer.EPISODIC, scope, "session.last_cmd", "x"));

        CapturingLlmClient client = new CapturingLlmClient();
        LlmPlanner planner = newPlanner(client, stub);

        planner.decide(buildDriverRequest(), sessionContext(), driverTools());

        assertNotNull(client.capturedUserPrompt);
        // PREFERENCE snippet 必须被 savedKeysFor 跳过,只在 preferenceKeysFor 段出现 1 次
        long preferenceKeyOccurrences = client.capturedUserPrompt.lines()
                .filter(line -> line.contains("preferred_temperature")).count();
        assertEquals("PREFERENCE snippet 不应重复出现",
                1L, preferenceKeyOccurrences);
        assertTrue("EPISODIC snippet 必须出现", client.capturedUserPrompt.contains("[episodic]"));
    }

    private AgentRequest buildDriverRequest() {
        return AgentRequest.builder("把主驾温度调到我喜欢", Actor.DRIVER)
                .sessionId("planner-memory")
                .build();
    }

    private static MemoryScope driverScope() {
        return new MemoryScope("demo-driver", VehicleZone.DRIVER);
    }

    private SessionContext sessionContext() {
        return sessionManager.getOrCreate("planner-memory");
    }

    private List<ToolDefinition> driverTools() {
        return registry.toToolDefinitions(VehicleZone.DRIVER);
    }

    private LlmPlanner newPlanner(LlmClient client, MemoryRecaller recaller) {
        ModelConfig config = new ModelConfig("test", "test", ApiProtocol.OPENAI_CHAT,
                "http://localhost/v1", "gpt-test", "", false);
        LlmPlanner planner = new LlmPlanner(client, config, memoryStore);
        if (recaller != null) planner.setMemoryRecaller(recaller);
        return planner;
    }

    /** 简易 fake LlmClient:捕获 userPrompt,返回最小合法 JSON。 */
    private static final class CapturingLlmClient implements LlmClient {
        volatile String capturedUserPrompt;

        @Override
        public String complete(ModelConfig config, String systemPrompt, String userPrompt) {
            capturedUserPrompt = userPrompt;
            return "{\"summary\":\"ok\",\"steps\":[]}";
        }

        @Override
        public String complete(ModelConfig config, String systemPrompt, String userPrompt,
                com.matrix.agent.identity.CancellationToken token, long deadlineAtMillis) {
            return complete(config, systemPrompt, userPrompt);
        }

    }
}
