package com.matrix.agent.task.prompt;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import com.matrix.agent.task.identity.Actor;
import com.matrix.agent.task.identity.AgentRequest;
import com.matrix.agent.task.identity.VehicleZone;
import com.matrix.agent.data.memory.MemoryLayer;
import com.matrix.agent.data.memory.MemoryScope;
import com.matrix.agent.data.memory.MemorySnippet;

/**
 * DefaultPromptBuilder 受控记忆投影测试——
 * 白名单 key + 范围校验 + SensitiveKeys 双重底线 + Episodic/Semantic/Working 层 value 永不进 prompt。
 *
 * <p>验证规则:
 * <ul>
 *   <li>PREFERENCE + 白名单 key(preferred_temperature/preferred_seat_level/preferred_media_volume)
 *       + 范围合法 value → value 投影到 prompt(让模型直接用,无需调工具);</li>
 *   <li>PREFERENCE + 白名单 key + 范围非法 value(如 preferred_temperature=999)→ deny;</li>
 *   <li>PREFERENCE + 非白名单 key(home_address / custom_x / system_instructions 等)→ deny,
 *       显示 "已保存,请用工具查询";</li>
 *   <li>EPISODIC 层所有 value 都不附(可能含 PII 回声);</li>
 *   <li>SEMANTIC 层所有 value 都不附(显式 PII 存储层);</li>
 *   <li>WORKING 层所有 value 都不附(用户上一轮原文);</li>
 *   <li>外层包 {@code <memory_context>} 边界(从 trusted_memory 重命名)+ 底部提示文案。</li>
 * </ul>
 */
public final class DefaultPromptBuilderPiiProjectionTest {

    private final DefaultPromptBuilder builder = new DefaultPromptBuilder();
    private final MemoryScope scope = new MemoryScope("demo-driver", VehicleZone.DRIVER);

    @Test
    public void preferenceNonPiiValueIsProjected() {
        List<MemorySnippet> snippets = Arrays.asList(
                MemorySnippet.of(MemoryLayer.PREFERENCE, scope, "preferred_temperature", "24"));

        String text = format(snippets);

        assertTrue("preference 非 PII key value 必须投影",
                text.contains("preferred_temperature: 24"));
        assertFalse("不应显示查询提示", text.contains("preferred_temperature (已保存"));
    }

    @Test
    public void preferencePiiKeyIsDenied() {
        List<MemorySnippet> snippets = Arrays.asList(
                MemorySnippet.of(MemoryLayer.PREFERENCE, scope, "home_address", "北京市朝阳区"));

        String text = format(snippets);

        assertTrue("PII key 仍展示 key 名", text.contains("home_address"));
        assertTrue("PII key 显示查询提示", text.contains("home_address (已保存,请用工具查询)"));
        assertFalse("PII value 永不投影", text.contains("北京市朝阳区"));
    }

    @Test
    public void episodicLayerValueAlwaysDenied() {
        List<MemorySnippet> snippets = Arrays.asList(
                MemorySnippet.of(MemoryLayer.EPISODIC, scope, "session#abcdef12", "导航回家"));

        String text = format(snippets);

        assertTrue("episodic 仍展示 key", text.contains("session#abcdef12"));
        assertTrue("episodic 显示查询提示", text.contains("(已保存,请用工具查询)"));
        assertFalse("episodic value 永不投影", text.contains("导航回家"));
    }

    @Test
    public void semanticLayerValueAlwaysDenied() {
        List<MemorySnippet> snippets = Arrays.asList(
                MemorySnippet.of(MemoryLayer.SEMANTIC, scope, "allergy.peanut", "严重过敏"));

        String text = format(snippets);

        assertTrue("semantic 仍展示 key", text.contains("allergy.peanut"));
        assertTrue("semantic 显示查询提示", text.contains("(已保存,请用工具查询)"));
        assertFalse("semantic value 永不投影", text.contains("严重过敏"));
    }

    @Test
    public void workingLayerValueAlwaysDenied() {
        List<MemorySnippet> snippets = Arrays.asList(
                MemorySnippet.of(MemoryLayer.WORKING, scope, "turn.last_query", "查电量"));

        String text = format(snippets);

        assertTrue("working 仍展示 key", text.contains("turn.last_query"));
        assertTrue("working 显示查询提示", text.contains("(已保存,请用工具查询)"));
        assertFalse("working value 永不投影", text.contains("查电量"));
    }

    @Test
    public void emptyMemoryDoesNotEmitMemoryContextBoundary() {
        String text = format(Collections.emptyList());

        assertFalse("空 memory 不应附 memory_context 边界", text.contains("<memory_context>"));
        assertFalse(text == null || text.isEmpty());
    }

    @Test
    public void mixedSnippetsProjectCorrectly() {
        List<MemorySnippet> snippets = Arrays.asList(
                MemorySnippet.of(MemoryLayer.PREFERENCE, scope, "preferred_temperature", "24"),
                MemorySnippet.of(MemoryLayer.PREFERENCE, scope, "home_address", "北京市"),
                MemorySnippet.of(MemoryLayer.SEMANTIC, scope, "fact.daughter", "小红"),
                MemorySnippet.of(MemoryLayer.EPISODIC, scope, "session#xyz", "导航"),
                MemorySnippet.of(MemoryLayer.WORKING, scope, "turn.last_q", "查电量"));

        String text = format(snippets);

        assertTrue("preferred_temperature 投影 value", text.contains("preferred_temperature: 24"));
        assertTrue("home_address 显示查询提示", text.contains("home_address (已保存"));
        assertFalse("home_address value 不投影", text.contains("北京市"));
        assertFalse("semantic value 不投影", text.contains("小红"));
        assertFalse("episodic value 不投影", text.contains("导航"));
        assertFalse("working value 不投影", text.contains("查电量"));
    }

    @Test
    public void memoryContextBoundaryTagsEmitted() {
        List<MemorySnippet> snippets = Arrays.asList(
                MemorySnippet.of(MemoryLayer.PREFERENCE, scope, "preferred_temperature", "24"));

        String text = format(snippets);

        assertTrue("必须有 <memory_context> 开标签", text.contains("<memory_context>"));
        assertTrue("必须有 </memory_context> 闭标签", text.contains("</memory_context>"));
        assertFalse("V0.5.4 后不应再有 <trusted_memory>", text.contains("<trusted_memory>"));
        assertTrue("必须有底部提示文案",
                text.contains("若需查询详情或更新,请调用 memory.semantic.get / memory.preference.get"));
    }

    private String format(List<MemorySnippet> snippets) {
        AgentRequest request = AgentRequest.builder("查电量", Actor.DRIVER)
                .sessionId("test-session").build();
        List<PromptSegment> segments = builder.buildSystemPrompt(request, snippets);
        // 拼接 BASE + RECALLED_MEMORY
        return DefaultPromptBuilder.join(segments);
    }
}
