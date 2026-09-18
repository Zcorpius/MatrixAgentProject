package com.matrix.agent.task.prompt;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.identity.VehicleZone;
import com.matrix.agent.data.memory.MemoryLayer;
import com.matrix.agent.data.memory.MemoryScope;
import com.matrix.agent.data.memory.MemorySnippet;

/**
 * DefaultPromptBuilder 白名单 + 类型/范围校验测试。
 *
 * <p>用户硬约束:"改为 allowlist,而非 denylist"。旧版 denylist("非 PII key 即放行")
 * 允许任意 preference key 的 value 进 prompt——包括 prompt-injection 文本作为 value 的情形。
 * 改为白名单:仅 3 个数值型偏好 key + 范围合法 value 才投影。
 *
 * <p>验证规则:
 * <ul>
 *   <li>白名单 key + 合法 value(preferred_temperature=24)→ 附 value</li>
 *   <li>白名单 key + 范围非法 value(preferred_temperature=999 / preferred_seat_level=99)→ deny</li>
 *   <li>白名单 key + 非数字 value → deny</li>
 *   <li>非白名单 key(custom_preference / system_instructions)→ deny</li>
 *   <li>非 PREFERENCE 层(Episodic / Semantic / Working)→ deny</li>
 * </ul>
 */
public final class DefaultPromptBuilderWhitelistProjectionTest {

    private final DefaultPromptBuilder builder = new DefaultPromptBuilder();
    private final MemoryScope scope = new MemoryScope("demo-driver", VehicleZone.DRIVER);

    @Test
    public void whitelistKeyWithValidValueIsProjected() {
        String text = format(Arrays.asList(
                MemorySnippet.of(MemoryLayer.PREFERENCE, scope, "preferred_temperature", "24"),
                MemorySnippet.of(MemoryLayer.PREFERENCE, scope, "preferred_seat_level", "2"),
                MemorySnippet.of(MemoryLayer.PREFERENCE, scope, "preferred_media_volume", "50")));

        assertTrue("preferred_temperature=24 投影", text.contains("preferred_temperature: 24"));
        assertTrue("preferred_seat_level=2 投影", text.contains("preferred_seat_level: 2"));
        assertTrue("preferred_media_volume=50 投影", text.contains("preferred_media_volume: 50"));
    }

    @Test
    public void whitelistKeyWithOutOfRangeValueIsDenied() {
        String text = format(Arrays.asList(
                MemorySnippet.of(MemoryLayer.PREFERENCE, scope, "preferred_temperature", "999"),
                MemorySnippet.of(MemoryLayer.PREFERENCE, scope, "preferred_seat_level", "99"),
                MemorySnippet.of(MemoryLayer.PREFERENCE, scope, "preferred_media_volume", "200")));

        assertTrue("preferred_temperature 仍展示 key 名",
                text.contains("preferred_temperature"));
        assertTrue("preferred_temperature=999 超范围 deny → 显示查询提示",
                text.contains("preferred_temperature (已保存,请用工具查询)"));
        assertFalse("preferred_temperature=999 不投影 value",
                text.contains("preferred_temperature: 999"));
        assertTrue("preferred_seat_level=99 deny",
                text.contains("preferred_seat_level (已保存"));
        assertTrue("preferred_media_volume=200 deny",
                text.contains("preferred_media_volume (已保存"));
    }

    @Test
    public void whitelistKeyWithLowerBoundaryEdgeCaseValid() {
        String text = format(Arrays.asList(
                MemorySnippet.of(MemoryLayer.PREFERENCE, scope, "preferred_temperature", "16"),
                MemorySnippet.of(MemoryLayer.PREFERENCE, scope, "preferred_temperature", "30"),
                MemorySnippet.of(MemoryLayer.PREFERENCE, scope, "preferred_temperature", "15"),
                MemorySnippet.of(MemoryLayer.PREFERENCE, scope, "preferred_temperature", "31")));

        // 16, 30 在范围 → 投影;15, 31 越界 → deny
        assertTrue("16 投影(下界)", text.contains("preferred_temperature: 16"));
        assertTrue("30 投影(上界)", text.contains("preferred_temperature: 30"));
        assertFalse("15 越界不投影", text.contains("preferred_temperature: 15"));
        assertFalse("31 越界不投影", text.contains("preferred_temperature: 31"));
    }

    @Test
    public void whitelistKeyWithNonNumericValueIsDenied() {
        String text = format(Collections.singletonList(
                MemorySnippet.of(MemoryLayer.PREFERENCE, scope, "preferred_temperature", "abc")));

        assertTrue("非数字 value deny → 显示查询提示",
                text.contains("preferred_temperature (已保存"));
        assertFalse("非数字 value 不投影",
                text.contains("preferred_temperature: abc"));
    }

    @Test
    public void nonWhitelistKeyIsDeniedEvenIfNotPii() {
        String text = format(Collections.singletonList(
                MemorySnippet.of(MemoryLayer.PREFERENCE, scope, "custom_preference", "value")));

        assertTrue("非白名单 key 仍展示 key 名", text.contains("custom_preference"));
        assertTrue("非白名单 key deny → 显示查询提示",
                text.contains("custom_preference (已保存,请用工具查询)"));
        assertFalse("非白名单 key value 不投影",
                text.contains("custom_preference: value"));
    }

    @Test
    public void episodicLayerAlwaysDeniedRegardlessOfKey() {
        // 即便 episodic snippet 的 key 恰好叫 preferred_temperature,也 deny(层判断在前)
        String text = format(Collections.singletonList(
                MemorySnippet.of(MemoryLayer.EPISODIC, scope, "preferred_temperature", "24")));

        assertTrue(text.contains("(已保存,请用工具查询)"));
        assertFalse("EPISODIC 层 preferred_temperature: 24 不应投影",
                text.contains("preferred_temperature: 24"));
    }

    private String format(List<MemorySnippet> snippets) {
        AgentRequest request = AgentRequest.builder("查电量", Actor.DRIVER)
                .sessionId("test").build();
        return DefaultPromptBuilder.join(builder.buildSystemPrompt(request, snippets));
    }
}
