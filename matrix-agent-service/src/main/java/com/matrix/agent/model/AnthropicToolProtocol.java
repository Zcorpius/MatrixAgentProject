package com.matrix.agent.model;

import com.matrix.agent.contract.ModelConfig;

import android.util.Log;

import com.matrix.agent.contract.AgentMessage;
import com.matrix.agent.contract.FinishReason;
import com.matrix.agent.contract.ModelTurn;
import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.contract.ToolDefinition;
import com.matrix.agent.contract.ToolParameterDefinition;
import com.matrix.agent.contract.schema.SchemaJsonWriter;
import com.matrix.agent.contract.schema.SchemaProjectionConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Anthropic Messages native tool-calling codec; HTTP and retries remain outside this class. */
final class AnthropicToolProtocol {
    private static final String TAG = "MatrixAgent";

    static JSONObject buildRequest(ModelConfig config, String system, List<AgentMessage> conversation,
            List<ToolDefinition> tools) throws Exception {
        if (tools == null || tools.isEmpty()) throw new IllegalArgumentException("ToolDefinition 不能为空");
        JSONArray wireTools = new JSONArray();
        for (ToolDefinition tool : tools) wireTools.put(toTool(tool));
        return new JSONObject().put("model", config.model).put("max_tokens", 2048)
                .put("temperature", 0.1).put("stream", false).put("system", system)
                .put("messages", toMessages(conversation)).put("tools", wireTools);
    }

    static ModelTurn parseResponse(JSONObject response, List<ToolDefinition> tools) throws Exception {
        JSONArray blocks = response.getJSONArray("content");
        StringBuilder text = new StringBuilder();
        java.util.List<ToolCall> calls = new java.util.ArrayList<>();
        Map<String, ToolDefinition> definitions = new LinkedHashMap<>();
        for (ToolDefinition tool : tools) definitions.put(tool.getModelName(), tool);
        java.util.Set<String> ids = new java.util.HashSet<>();
        int toolUses = 0;
        for (int i = 0; i < blocks.length(); i++) {
            JSONObject block = blocks.getJSONObject(i);
            if ("text".equals(block.getString("type"))) {
                text.append(block.optString("text", ""));
            } else if ("tool_use".equals(block.getString("type"))) {
                toolUses++;
                String id = block.optString("id", "");
                if (id == null || id.trim().isEmpty()) {
                    throw new IllegalStateException("Anthropic 响应第 " + i + " 个 tool_use block 缺失 id");
                }
                if (!ids.add(id)) throw new IllegalStateException("Anthropic 响应中 tool_use 出现重复 id=" + id);
                String name = block.getString("name");
                ToolDefinition definition = definitions.get(name);
                if (definition == null) throw new IllegalStateException("Anthropic 返回未注册 Tool：" + name);
                Object input = block.opt("input");
                calls.add(ToolCall.withId(id, definition.getCapabilityName(),
                        input instanceof JSONObject ? toMap((JSONObject) input) : new LinkedHashMap<>()));
            }
        }
        String rawStop = response.optString("stop_reason", "");
        Log.d(TAG, "[Http] anthropic parse blocks=" + blocks.length() + " tool_use=" + toolUses);
        FinishReason reason = mapStopReason(rawStop);
        if (reason == FinishReason.LENGTH) return ModelTurn.of(text.toString(), FinishReason.LENGTH);
        if (calls.isEmpty()) {
            if (reason == FinishReason.STOP && text.toString().trim().isEmpty()) reason = FinishReason.NONE;
            return ModelTurn.of(text.toString(), reason);
        }
        if (reason != FinishReason.TOOL_CALLS) {
            Log.w(TAG, "[Http] anthropic protocol error: tool_use with stop_reason=" + rawStop);
            return ModelTurn.of(text.toString(), FinishReason.NONE);
        }
        return ModelTurn.ofToolCalls(calls, text.toString());
    }

    private static JSONArray toMessages(List<AgentMessage> conversation) throws Exception {
        JSONArray messages = new JSONArray();
        JSONObject pendingResults = null;
        for (AgentMessage item : conversation) {
            switch (item.getRole()) {
                case SYSTEM: break;
                case USER:
                    if (pendingResults != null) { messages.put(pendingResults); pendingResults = null; }
                    messages.put(new JSONObject().put("role", "user").put("content", item.getContent()));
                    break;
                case ASSISTANT:
                    if (pendingResults != null) { messages.put(pendingResults); pendingResults = null; }
                    messages.put(new JSONObject().put("role", "assistant").put("content", assistantContent(item)));
                    break;
                case TOOL:
                    if (pendingResults == null) pendingResults = new JSONObject().put("role", "user");
                    JSONArray content = pendingResults.optJSONArray("content");
                    if (content == null) content = new JSONArray();
                    content.put(new JSONObject().put("type", "tool_result")
                            .put("tool_use_id", item.getToolCallId()).put("content", item.getContent()));
                    pendingResults.put("content", content);
                    break;
            }
        }
        if (pendingResults != null) messages.put(pendingResults);
        return messages;
    }

    private static JSONArray assistantContent(AgentMessage item) throws Exception {
        JSONArray content = new JSONArray();
        if (item.getContent() != null && !item.getContent().isEmpty()) {
            content.put(new JSONObject().put("type", "text").put("text", item.getContent()));
        }
        for (ToolCall call : item.getToolCalls()) content.put(new JSONObject().put("type", "tool_use")
                .put("id", call.getStepId()).put("name", modelName(call.getCapabilityName()))
                .put("input", toJson(call.getArguments())));
        if (content.length() == 0) content.put(new JSONObject().put("type", "text").put("text", ""));
        return content;
    }

    private static JSONObject toTool(ToolDefinition tool) throws Exception {
        JSONObject schema = tool.getParametersSchema() == null ? legacySchema(tool)
                : SchemaJsonWriter.INSTANCE.write(tool.getParametersSchema(), SchemaProjectionConfig.ANTHROPIC_FULL);
        return new JSONObject().put("name", tool.getModelName()).put("description", tool.getDescription())
                .put("input_schema", schema);
    }

    private static JSONObject legacySchema(ToolDefinition tool) throws Exception {
        JSONObject properties = new JSONObject(); JSONArray required = new JSONArray();
        for (ToolParameterDefinition parameter : tool.getParameters()) {
            JSONObject schema = new JSONObject().put("type", jsonType(parameter.getType()))
                    .put("description", parameter.getDescription());
            if (!parameter.getEnumValues().isEmpty()) { JSONArray values = new JSONArray();
                for (String value : parameter.getEnumValues()) values.put(value); schema.put("enum", values); }
            if (parameter.getMinimum() != null) schema.put("minimum", parameter.getMinimum());
            if (parameter.getMaximum() != null) schema.put("maximum", parameter.getMaximum());
            properties.put(parameter.getName(), schema); if (parameter.isRequired()) required.put(parameter.getName());
        }
        return new JSONObject().put("type", "object").put("properties", properties)
                .put("required", required).put("additionalProperties", false);
    }

    private static JSONObject toJson(Map<String, Object> values) throws Exception {
        JSONObject object = new JSONObject();
        if (values != null) for (Map.Entry<String, Object> item : values.entrySet())
            object.put(item.getKey(), item.getValue() == null ? JSONObject.NULL : item.getValue());
        return object;
    }

    private static Map<String, Object> toMap(JSONObject object) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>(); java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) { String key = keys.next(); Object value = object.get(key);
            result.put(key, value == JSONObject.NULL ? null : value); }
        return result;
    }

    private static String modelName(String name) { return name.replaceAll("[^a-zA-Z0-9_-]", "_"); }
    private static String jsonType(ToolParameterDefinition.Type type) {
        switch (type) { case INTEGER: return "integer"; case NUMBER: return "number";
            case BOOLEAN: return "boolean"; case STRING: default: return "string"; }
    }
    private static FinishReason mapStopReason(String raw) {
        if (raw == null || raw.isEmpty()) return FinishReason.NONE;
        switch (raw) { case "end_turn": case "stop_sequence": return FinishReason.STOP;
            case "max_tokens": return FinishReason.LENGTH; case "tool_use": return FinishReason.TOOL_CALLS;
            default: return FinishReason.NONE; }
    }
}
