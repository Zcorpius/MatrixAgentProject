package com.matrix.agent.model;

import com.matrix.agent.task.AgentMessage;
import com.matrix.agent.task.capability.ToolDefinition;
import com.matrix.agent.task.capability.schema.CanonicalSchema;
import com.matrix.agent.task.capability.schema.SchemaJsonWriter;
import com.matrix.agent.task.capability.schema.SchemaProjectionConfig;
import com.matrix.agent.task.tool.ToolCall;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MNN structured 请求编解码：把 MatrixAgent 的 {@link AgentMessage} conversation + {@link ToolDefinition}
 * tools 编码成 MNN cpp {@code applyStructuredChatTemplate} 期望的 OpenAI 风格 JSON。
 *
 * <p><b>system 去重</b>：AgentEngine 已把 systemPrompt 作为 {@code AgentMessage.system()} 放进
 * conversation 首条（AgentEngine L340），并同时传 request.getSystemPrompt()。本 codec <b>以 conversation
 * 的 SYSTEM 为准</b>——首条 SYSTEM 写入 messages[0]，后续内容<b>相同</b>的 SYSTEM 跳过（去重），
 * 内容<b>不同</b>的 SYSTEM（如摘要）合并进 messages[0] 的 content，保证最终只有一条 system message、
 * 且 systemPrompt 文本只出现一次。不再使用 request.getSystemPrompt()。
 *
 * <p><b>多轮历史配对</b>：ASSISTANT 的 tool_calls[].id（=ToolCall.stepId）与紧随的 TOOL 消息的
 * tool_call_id 严格对应；MNN 每轮 reset 必须传全历史，本 codec 完整映射不丢。
 *
 * <p>tool name 用 {@link ToolDefinition#getModelName()}（与 MatrixAgent 云端 OpenAI 路径一致），
 * 反向解析时由 {@code modelToCapability} 反查回 capabilityName。
 */
final class MnnStructuredRequestCodec {

    private MnnStructuredRequestCodec() {}

    /** capabilityName → modelName（tools 定义侧）。 */
    static Map<String, String> capabilityToModelName(List<ToolDefinition> tools) {
        Map<String, String> m = new LinkedHashMap<>();
        if (tools == null) return m;
        for (ToolDefinition t : tools) m.put(t.getCapabilityName(), t.getModelName());
        return m;
    }

    /** modelName → capabilityName（解析模型输出 tool_calls 时反查）。 */
    static Map<String, String> modelToCapability(List<ToolDefinition> tools) {
        Map<String, String> m = new LinkedHashMap<>();
        if (tools == null) return m;
        for (ToolDefinition t : tools) m.put(t.getModelName(), t.getCapabilityName());
        return m;
    }

    /** tools → OpenAI tools JSON 数组字符串；null/空返回 null（不传 tools）。 */
    static String buildToolsJson(List<ToolDefinition> tools) throws Exception {
        if (tools == null || tools.isEmpty()) return null;
        JSONArray arr = new JSONArray();
        for (ToolDefinition t : tools) arr.put(buildTool(t));
        return arr.toString();
    }

    private static JSONObject buildTool(ToolDefinition tool) throws Exception {
        JSONObject parameters;
        CanonicalSchema schema = tool.getParametersSchema();
        if (schema != null) {
            parameters = SchemaJsonWriter.INSTANCE.write(schema, SchemaProjectionConfig.OPENAI_STRICT);
        } else {
            // 全部 capability 带 schema；fallback 用空 object schema（极少触发）
            parameters = new JSONObject().put("type", "object")
                    .put("properties", new JSONObject())
                    .put("required", new JSONArray());
        }
        return new JSONObject().put("type", "function")
                .put("function", new JSONObject()
                        .put("name", tool.getModelName())
                        .put("description", tool.getDescription())
                        .put("parameters", parameters));
    }

    /**
     * conversation → OpenAI messages JSON。system 恰好一条（首条 + 去重相同 + 合并不同）。
     * MNN 每轮 reset，必须传完整历史。
     */
    static String buildMessagesJson(List<AgentMessage> conversation, Map<String, String> capToModel)
            throws Exception {
        JSONArray messages = new JSONArray();
        boolean systemWritten = false;
        int systemIndex = -1;
        String firstSystemContent = null;

        for (AgentMessage msg : conversation) {
            switch (msg.getRole()) {
                case SYSTEM: {
                    String content = msg.getContent() == null ? "" : msg.getContent();
                    if (!systemWritten) {
                        messages.put(new JSONObject().put("role", "system").put("content", content));
                        systemWritten = true;
                        systemIndex = messages.length() - 1;
                        firstSystemContent = content;
                    } else if (!content.equals(firstSystemContent)) {
                        // 对比首条值（非累积值）；用 systemIndex（不假设 messages[0]）
                        JSONObject sysMsg = messages.getJSONObject(systemIndex);
                        sysMsg.put("content", sysMsg.getString("content") + "\n" + content);
                    }
                    break;
                }
                case USER:
                    messages.put(new JSONObject().put("role", "user").put("content", msg.getContent()));
                    break;
                case ASSISTANT: {
                    JSONObject a = new JSONObject().put("role", "assistant");
                    String content = msg.getContent();
                    a.put("content", content == null || content.isEmpty() ? JSONObject.NULL : content);
                    if (!msg.getToolCalls().isEmpty()) {
                        JSONArray toolCalls = new JSONArray();
                        for (ToolCall call : msg.getToolCalls()) {
                            String modelName = capToModel.get(call.getCapabilityName());
                            if (modelName == null) modelName = toModelToolName(call.getCapabilityName());
                            toolCalls.put(new JSONObject()
                                    .put("id", call.getStepId())
                                    .put("type", "function")
                                    .put("function", new JSONObject()
                                            .put("name", modelName)
                                            .put("arguments", toJson(call.getArguments()).toString())));
                        }
                        a.put("tool_calls", toolCalls);
                    }
                    messages.put(a);
                    break;
                }
                case TOOL: {
                    JSONObject t = new JSONObject().put("role", "tool")
                            .put("content", msg.getContent() == null ? JSONObject.NULL : msg.getContent())
                            .put("tool_call_id", msg.getToolCallId());
                    if (msg.getToolName() != null && !msg.getToolName().isEmpty()) {
                        String modelName = capToModel.get(msg.getToolName());
                        t.put("name", modelName != null ? modelName : toModelToolName(msg.getToolName()));
                    }
                    messages.put(t);
                    break;
                }
            }
        }
        return messages.toString();
    }

    /** capabilityName → 合法 model function name（与 ModelApiClient.toModelToolName 一致）。 */
    static String toModelToolName(String capabilityName) {
        return capabilityName.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private static JSONObject toJson(Map<String, Object> map) throws Exception {
        JSONObject obj = new JSONObject();
        if (map == null) return obj;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            obj.put(e.getKey(), e.getValue() == null ? JSONObject.NULL : e.getValue());
        }
        return obj;
    }
}
