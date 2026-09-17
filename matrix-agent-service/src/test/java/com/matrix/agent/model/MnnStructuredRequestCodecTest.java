package com.matrix.agent.model;

import com.matrix.agent.task.AgentMessage;
import com.matrix.agent.task.tool.ToolCall;

import org.json.JSONArray;
import org.json.JSONObject;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * MnnStructuredRequestCodec round-trip 单测——评审 P0-1/P0-5 硬要求：
 * system 恰好一次（去重）、tool_calls[].id ↔ tool.tool_call_id 严格配对。
 */
public class MnnStructuredRequestCodecTest {

    @Test
    public void systemPromptAppearsExactlyOnceWhenDuplicated() throws Exception {
        // 模拟 AgentEngine 行为：systemPrompt 作为 SYSTEM 放进 conversation（L340）。
        // 若配置错误导致重复 SYSTEM，codec 应去重——最终只一条 system、文本只出现一次。
        String sysPrompt = "你是 MatrixAgent 安全助手";
        List<AgentMessage> conv = new ArrayList<>();
        conv.add(AgentMessage.system(sysPrompt));
        conv.add(AgentMessage.user("你好"));
        conv.add(AgentMessage.system(sysPrompt)); // 重复

        String json = MnnStructuredRequestCodec.buildMessagesJson(conv, new LinkedHashMap<String, String>());
        JSONArray messages = new JSONArray(json);

        int systemCount = 0;
        for (int i = 0; i < messages.length(); i++) {
            if ("system".equals(messages.getJSONObject(i).getString("role"))) systemCount++;
        }
        assertEquals("只应有一条 system message", 1, systemCount);
        assertEquals("system prompt 文本应只出现一次", 1, countOccurrences(json, sysPrompt));
    }

    @Test
    public void distinctSystemMessagesMergedIntoOne() throws Exception {
        // 不同 SYSTEM（如摘要）应合并进首条，仍保持只一条 system message
        List<AgentMessage> conv = new ArrayList<>();
        conv.add(AgentMessage.system("主提示词"));
        conv.add(AgentMessage.summary("历史摘要")); // summary 也是 SYSTEM，不同内容

        String json = MnnStructuredRequestCodec.buildMessagesJson(conv, new LinkedHashMap<String, String>());
        JSONArray messages = new JSONArray(json);
        int systemCount = 0;
        for (int i = 0; i < messages.length(); i++) {
            if ("system".equals(messages.getJSONObject(i).getString("role"))) systemCount++;
        }
        assertEquals("不同 SYSTEM 应合并成一条", 1, systemCount);
        assertTrue("合并后应含主提示词", messages.getJSONObject(0).getString("content").contains("主提示词"));
        assertTrue("合并后应含摘要", messages.getJSONObject(0).getString("content").contains("历史摘要"));
    }

    @Test
    public void toolCallIdPairsWithFollowingToolMessage() throws Exception {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("temperature", 24);
        ToolCall call = new ToolCall("climate.set", args); // 自动 UUID stepId

        List<AgentMessage> conv = new ArrayList<>();
        conv.add(AgentMessage.system("sys"));
        conv.add(AgentMessage.user("把温度调到 24"));
        conv.add(AgentMessage.assistant("好的", Collections.singletonList(call)));
        conv.add(AgentMessage.tool(call.getStepId(), "climate.set", "已设置 24 度"));

        Map<String, String> capToModel = new LinkedHashMap<>();
        capToModel.put("climate.set", "climate_set");

        String json = MnnStructuredRequestCodec.buildMessagesJson(conv, capToModel);
        JSONArray messages = new JSONArray(json);

        String assistantCallId = null;
        String toolCallId = null;
        for (int i = 0; i < messages.length(); i++) {
            JSONObject m = messages.getJSONObject(i);
            String role = m.getString("role");
            if ("assistant".equals(role) && m.has("tool_calls")) {
                assistantCallId = m.getJSONArray("tool_calls").getJSONObject(0).getString("id");
                assertEquals("function name 应为 modelName", "climate_set",
                        m.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("name"));
            }
            if ("tool".equals(role)) {
                toolCallId = m.getString("tool_call_id");
            }
        }
        assertEquals("tool_call_id 必须与 assistant tool_calls[].id 严格配对",
                assistantCallId, toolCallId);
    }

    private static int countOccurrences(String text, String sub) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(sub, idx)) >= 0) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}
