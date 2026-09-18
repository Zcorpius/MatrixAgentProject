package com.matrix.agent.task.prompt;

import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.identity.InputSource;
import com.matrix.agent.identity.VehicleZone;
import com.matrix.agent.data.memory.MemoryLayer;
import com.matrix.agent.data.memory.MemoryScope;
import com.matrix.agent.data.memory.MemorySnippet;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * DefaultPromptBuilder 契约测试。
 *
 * <p>验证:
 * <ul>
 *   <li>空 memory / 多条 memory 各场景的段落顺序与文案稳定性;</li>
 *   <li>BASE 段不变性(旧版兼容契约);</li>
 *   <li>RECALLED_MEMORY 段格式 ([layer] key,不含 value);</li>
 *   <li>zone=DRIVER/PASSENGER 各自的 BASE 段差异。</li>
 * </ul>
 *
 * <p>不影响 289 旧版测试——本测试独立,AgentEngine 主路径未切换到 PromptBuilder。
 */
public final class DefaultPromptBuilderTest {
    private final DefaultPromptBuilder builder = new DefaultPromptBuilder();

    @Test
    public void emptyMemoryProducesBaseOnly() {
        AgentRequest request = driverRequest("查电量");
        List<PromptSegment> segments = builder.buildSystemPrompt(request, Collections.emptyList());

        assertEquals(1, segments.size());
        assertEquals(PromptSegment.Type.BASE, segments.get(0).getType());
        assertBaseShape(segments.get(0).getText(), request);
    }

    @Test
    public void nullMemoryTreatedAsEmpty() {
        AgentRequest request = driverRequest("查电量");
        List<PromptSegment> segments = builder.buildSystemPrompt(request, null);

        assertEquals("null memory 应等价于 empty(仅 BASE 段)", 1, segments.size());
    }

    @Test
    public void multipleSnippetsAppendRecalledMemoryAfterBase() {
        AgentRequest request = driverRequest("把温度调到我喜欢");
        MemoryScope scope = new MemoryScope("demo-driver", VehicleZone.DRIVER);
        List<MemorySnippet> snippets = Arrays.asList(
                MemorySnippet.of(MemoryLayer.PREFERENCE, scope, "user.preference.temperature", "24"),
                MemorySnippet.of(MemoryLayer.EPISODIC, scope, "session.last_cmd", "导航回家"),
                MemorySnippet.of(MemoryLayer.WORKING, scope, "turn.last_query", "查电量"));

        List<PromptSegment> segments = builder.buildSystemPrompt(request, snippets);

        assertEquals("BASE + RECALLED_MEMORY 共 2 段", 2, segments.size());
        assertEquals(PromptSegment.Type.BASE, segments.get(0).getType());
        assertEquals(PromptSegment.Type.RECALLED_MEMORY, segments.get(1).getType());

        String memoryText = segments.get(1).getText();
        assertTrue("召回段必须含标题", memoryText.contains("已召回的 Memory"));
        assertTrue("召回段必须含 [preference] key", memoryText.contains("[preference] user.preference.temperature"));
        assertTrue("召回段必须含 [episodic] key", memoryText.contains("[episodic] session.last_cmd"));
        assertTrue("召回段必须含 [working] key", memoryText.contains("[working] turn.last_query"));
        // 不含 value——避免 prompt injection
        assertTrue("召回段不应含 snippet value",
                !memoryText.contains("\"24\"") && !memoryText.contains("导航回家") && !memoryText.contains("查电量"));
    }

    @Test
    public void baseSegmentDiffersByZone() {
        AgentRequest driver = driverRequest("查电量");
        AgentRequest passenger = AgentRequest.builder("查电量", Actor.PASSENGER)
                .sessionId("pax-session")
                .build();

        String driverBase = builder.buildSystemPrompt(driver, Collections.emptyList()).get(0).getText();
        String passengerBase = builder.buildSystemPrompt(passenger, Collections.emptyList()).get(0).getText();

        assertTrue("DRIVER BASE 段必须含 DRIVER zone", driverBase.contains("DRIVER"));
        assertTrue("PASSENGER BASE 段必须含 PASSENGER zone", passengerBase.contains("PASSENGER"));
        assertTrue("DRIVER 与 PASSENGER BASE 段必须不同", !driverBase.equals(passengerBase));
    }

    @Test
    public void joinConcatenatesSegments() {
        AgentRequest request = driverRequest("查电量");
        List<PromptSegment> segments = builder.buildSystemPrompt(request, Collections.emptyList());

        String joined = DefaultPromptBuilder.join(segments);
        assertNotNull(joined);
        assertTrue("join 后必须含 BASE 文案", joined.contains("你是车机 AI 助理"));
    }

    private static AgentRequest driverRequest(String text) {
        return AgentRequest.builder(text, Actor.DRIVER).sessionId("test-session").build();
    }

    private static void assertBaseShape(String base, AgentRequest request) {
        assertTrue("BASE 必须含开场白", base.contains("你是车机 AI 助理"));
        assertTrue("BASE 必须含 actor=" + request.getActor(),
                base.contains(String.valueOf(request.getActor())));
        assertTrue("BASE 必须含 zone=" + request.getOccupantZone(),
                base.contains(String.valueOf(request.getOccupantZone())));
        assertTrue("BASE 必须含 PARAMETER_REJECTED 提示", base.contains("PARAMETER_REJECTED"));
        assertTrue("BASE 必须含 CAPABILITY_REJECTED 提示", base.contains("CAPABILITY_REJECTED"));
    }
}
