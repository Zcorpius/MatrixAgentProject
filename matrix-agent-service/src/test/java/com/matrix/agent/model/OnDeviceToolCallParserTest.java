package com.matrix.agent.model;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * OnDeviceToolCallParser fail-closed 单测——评审 P0-4：
 * 未知工具/重复 ID/坏 JSON → 整轮失败（不部分执行）；缺 id 生成本地稳定 id。
 */
public class OnDeviceToolCallParserTest {

    private Map<String, String> modelToCap() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("climate_set", "climate.set");
        return m;
    }

    @Test
    public void parsesNormalToolCallWithObjectAndStringArguments() {
        // arguments 为 JSON 字符串（OpenAI 标准）
        String output = "{\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"climate_set\",\"arguments\":\"{\\\"temp\\\":24}\"}}]}";
        OnDeviceToolCallParser.ParseResult r = OnDeviceToolCallParser.parse(output, modelToCap());
        assertFalse(r.protocolError);
        assertEquals(1, r.calls.size());
        assertEquals("climate.set", r.calls.get(0).getCapabilityName());
        assertEquals(24, ((Number) r.calls.get(0).getArguments().get("temp")).intValue());
        assertEquals("call_1", r.calls.get(0).getStepId());
    }

    @Test
    public void failClosedOnUnknownTool() {
        String output = "{\"tool_calls\":[{\"id\":\"c1\",\"function\":"
                + "{\"name\":\"unknown_tool\",\"arguments\":\"{}\"}}]}";
        OnDeviceToolCallParser.ParseResult r = OnDeviceToolCallParser.parse(output, modelToCap());
        assertTrue("未知工具应整轮失败（fail-closed）", r.protocolError);
        assertTrue(r.calls.isEmpty());
    }

    @Test
    public void failClosedOnDuplicateId() {
        String output = "{\"tool_calls\":["
                + "{\"id\":\"dup\",\"function\":{\"name\":\"climate_set\",\"arguments\":\"{}\"}},"
                + "{\"id\":\"dup\",\"function\":{\"name\":\"climate_set\",\"arguments\":\"{}\"}}]}";
        OnDeviceToolCallParser.ParseResult r = OnDeviceToolCallParser.parse(output, modelToCap());
        assertTrue("重复 ID 应整轮失败", r.protocolError);
    }

    @Test
    public void failClosedOnCorruptArguments() {
        String output = "{\"tool_calls\":[{\"id\":\"c1\",\"function\":"
                + "{\"name\":\"climate_set\",\"arguments\":\"{bad json\"}}]}";
        OnDeviceToolCallParser.ParseResult r = OnDeviceToolCallParser.parse(output, modelToCap());
        assertTrue("坏 arguments JSON 应整轮失败", r.protocolError);
    }

    @Test
    public void missingIdGeneratesLocalStableId() {
        String output = "{\"tool_calls\":[{\"function\":"
                + "{\"name\":\"climate_set\",\"arguments\":\"{\\\"temp\\\":24}\"}}]}";
        OnDeviceToolCallParser.ParseResult r = OnDeviceToolCallParser.parse(output, modelToCap());
        assertFalse(r.protocolError);
        assertEquals(1, r.calls.size());
        String id = r.calls.get(0).getStepId();
        assertNotNull(id);
        assertTrue("缺 id 时应生成本地稳定 id", !id.isEmpty());
    }

    @Test
    public void plainTextReturnsAsText() {
        OnDeviceToolCallParser.ParseResult r = OnDeviceToolCallParser.parse("这是纯文本回复", modelToCap());
        assertFalse(r.protocolError);
        assertTrue(r.calls.isEmpty());
        assertTrue(r.textContent.contains("纯文本"));
    }

    @Test
    public void fencedJsonBlockIsExtracted() {
        String output = "我来调用工具：\n```json\n{\"tool_calls\":[{\"id\":\"c1\",\"function\":"
                + "{\"name\":\"climate_set\",\"arguments\":\"{}\"}}]}\n```";
        OnDeviceToolCallParser.ParseResult r = OnDeviceToolCallParser.parse(output, modelToCap());
        assertFalse(r.protocolError);
        assertEquals(1, r.calls.size());
    }

    // —— Qwen3 / Hermes <tool_call> 标签格式（Qwen3 jinja.chat_template 默认产出）——

    @Test
    public void parsesQwen3ToolCallTagFormat() {
        String output = "<tool_call>\n{\"name\": \"climate_set\", \"arguments\": {\"temp\": 24}}\n</tool_call>";
        OnDeviceToolCallParser.ParseResult r = OnDeviceToolCallParser.parse(output, modelToCap());
        assertFalse(r.protocolError);
        assertEquals(1, r.calls.size());
        assertEquals("climate.set", r.calls.get(0).getCapabilityName());
        assertEquals(24, ((Number) r.calls.get(0).getArguments().get("temp")).intValue());
    }

    @Test
    public void parsesMultipleQwen3ToolCallTags() {
        String output = "<tool_call>\n{\"name\": \"climate_set\", \"arguments\": {}}\n</tool_call>\n"
                + "<tool_call>\n{\"name\": \"climate_set\", \"arguments\": {}}\n</tool_call>";
        OnDeviceToolCallParser.ParseResult r = OnDeviceToolCallParser.parse(output, modelToCap());
        // 两个同名 call，本地稳定 id 基于 name+args+index → id 不同，都接受
        assertFalse(r.protocolError);
        assertEquals(2, r.calls.size());
    }

    @Test
    public void qwen3ThinkingThenToolCall() {
        String output = "<think>用户要调温度，调用 climate_set。</think>\n"
                + "<tool_call>\n{\"name\": \"climate_set\", \"arguments\": {\"temp\": 24}}\n</tool_call>";
        OnDeviceToolCallParser.ParseResult r = OnDeviceToolCallParser.parse(output, modelToCap());
        assertFalse(r.protocolError);
        assertEquals(1, r.calls.size());
        // think 段被 strip，不计入 textContent
        assertFalse(r.textContent.contains("用户要调温度"));
    }

    @Test
    public void qwen3TagWithCorruptJsonFailsClosed() {
        String output = "<tool_call>\n{bad}\n</tool_call>"; // {bad} 非合法 JSON
        OnDeviceToolCallParser.ParseResult r = OnDeviceToolCallParser.parse(output, modelToCap());
        assertTrue("Qwen3 标签内坏 JSON 应整轮失败", r.protocolError);
    }

    @Test
    public void unclosedToolCallTagFailsClosed() {
        // P0-4: <tool_call> 开标签但无 </tool_call>（截断/畸形）→ 整轮 fail-closed，不当文本跳过
        String output = "<tool_call>\n{\"name\": \"climate_set\", \"arguments\": {\"temp\": 24}";
        OnDeviceToolCallParser.ParseResult r = OnDeviceToolCallParser.parse(output, modelToCap());
        assertTrue("未闭合 <tool_call> 应整轮失败", r.protocolError);
    }

    @Test
    public void pairedTagWithNonJsonContentFailsClosed() {
        // P0-B: 成对 <tool_call>...</tool_call> 但内容非 JSON → 整轮 fail-closed，不跳过让后续有效 call 执行
        String output = "<tool_call>not a json</tool_call>";
        OnDeviceToolCallParser.ParseResult r = OnDeviceToolCallParser.parse(output, modelToCap());
        assertTrue("成对但内容非法应整轮失败", r.protocolError);
    }

    @Test
    public void extraClosingTagFailsClosed() {
        // P0-3: 多余 </tool_call>（闭多于开）可携带有效 call 绕过校验 → 开闭不匹配 fail-closed
        String output = "</tool_call><tool_call>\n{\"name\": \"climate_set\", \"arguments\": {}}\n</tool_call>";
        OnDeviceToolCallParser.ParseResult r = OnDeviceToolCallParser.parse(output, modelToCap());
        assertTrue("多余 </tool_call>（开闭不匹配）应整轮失败", r.protocolError);
    }
}
