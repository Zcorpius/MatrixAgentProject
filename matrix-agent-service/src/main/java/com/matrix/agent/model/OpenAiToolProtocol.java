package com.matrix.agent.model;

import android.util.Log;

import com.matrix.agent.task.AgentMessage;
import com.matrix.agent.task.FinishReason;
import com.matrix.agent.task.ModelTurn;
import com.matrix.agent.task.tool.ToolCall;
import com.matrix.agent.task.capability.ToolDefinition;
import com.matrix.agent.task.capability.ToolParameterDefinition;
import com.matrix.agent.task.capability.schema.CanonicalSchema;
import com.matrix.agent.task.capability.schema.SchemaJsonWriter;
import com.matrix.agent.task.capability.schema.SchemaProjectionConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** OpenAI-compatible native tool-calling wire adapter; it owns no retry or transport policy. */
final class OpenAiToolProtocol {
    private static final String TAG = "MatrixAgent";

    static JSONObject buildSingleTurnRequest(ModelConfig config, String system, String user,
            List<ToolDefinition> tools) throws Exception {
        requireTools(tools);
        JSONArray toolArray = new JSONArray();
        for (ToolDefinition tool : tools) toolArray.put(toTool(tool));
        return new JSONObject().put("model", config.model).put("stream", false)
                .put("temperature", 0.1)
                .put("messages", new JSONArray().put(message("system", system))
                        .put(message("user", user)))
                .put("tools", toolArray).put("tool_choice", "auto");
    }

    static ModelTurn parseSingleTurnResponse(ModelConfig config, JSONObject response,
            List<ToolDefinition> tools) throws Exception {
        JSONObject message = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
        JSONArray toolCalls = message.optJSONArray("tool_calls");
        if (toolCalls == null || toolCalls.length() == 0) {
            String finishReason = response.getJSONArray("choices").getJSONObject(0)
                    .optString("finish_reason", "(missing)");
            String content = message.optString("content", "");
            com.matrix.agent.task.SafeLog.e(TAG, "[Http] no tool_calls in response. finish_reason="
                    + finishReason + " contentChars=" + content.length() + " contentHead="
                    + com.matrix.agent.task.SafeLog.PROVIDER_RAW_PLACEHOLDER + " respBytes="
                    + response.toString().length());
            throw new IllegalStateException("模型未返回 tool_calls；请确认模型支持原生 Tool Calling，或显式切换兼容模式");
        }
        Map<String, ToolDefinition> definitions = definitionsByModelName(tools);
        List<ToolCall> steps = new ArrayList<>();
        for (int index = 0; index < toolCalls.length(); index++) {
            JSONObject function = toolCalls.getJSONObject(index).getJSONObject("function");
            ToolDefinition definition = requireDefinition(definitions, function.getString("name"));
            steps.add(new ToolCall(definition.getCapabilityName(), toMap(arguments(function.opt("arguments")))));
        }
        String content = message.optString("content", "").trim();
        String summary = "Native Tool Calling/" + config.displayName;
        if (!content.isEmpty() && !"null".equals(content)) summary += "：" + content;
        Log.d(TAG, "[Http] openai parse tool_calls=" + toolCalls.length() + " mapped=" + steps.size());
        return ModelTurn.ofToolCalls(steps, summary);
    }

    static JSONObject buildConversationRequest(ModelConfig config, String system,
            List<AgentMessage> conversation, List<ToolDefinition> tools) throws Exception {
        requireTools(tools);
        JSONArray toolArray = new JSONArray();
        Map<String, String> names = new LinkedHashMap<>();
        for (ToolDefinition tool : tools) {
            toolArray.put(toTool(tool));
            names.put(tool.getCapabilityName(), tool.getModelName());
        }
        JSONArray messages = new JSONArray().put(message("system", system));
        JSONArray serialized = toMessages(conversation, names);
        for (int i = 0; i < serialized.length(); i++) messages.put(serialized.getJSONObject(i));
        return new JSONObject().put("model", config.model).put("stream", false)
                .put("temperature", 0.1).put("messages", messages)
                .put("tools", toolArray).put("tool_choice", "auto");
    }

    static ModelTurn parseConversationResponse(JSONObject response, List<ToolDefinition> tools)
            throws Exception {
        JSONObject choice = response.getJSONArray("choices").getJSONObject(0);
        JSONObject message = choice.getJSONObject("message");
        JSONArray toolCalls = message.optJSONArray("tool_calls");
        String content = message.optString("content", "");
        String rawFinish = choice.optString("finish_reason", "");
        if (mapFinishReason(rawFinish) == FinishReason.LENGTH) {
            Log.w(TAG, "[Http] openai-native LENGTH short-circuit finish_reason=" + rawFinish
                    + " toolCallCount=" + (toolCalls == null ? 0 : toolCalls.length()));
            return ModelTurn.of(content, FinishReason.LENGTH);
        }
        if (toolCalls == null || toolCalls.length() == 0) {
            FinishReason mapped = mapFinishReason(rawFinish);
            if (mapped == FinishReason.STOP && (content == null || content.trim().isEmpty())) {
                mapped = FinishReason.NONE;
            }
            return ModelTurn.of(content, mapped);
        }
        if (mapFinishReason(rawFinish) != FinishReason.TOOL_CALLS) {
            Log.w(TAG, "[Http] openai-native protocol error: tool_calls with finish_reason=" + rawFinish);
            return ModelTurn.of(content, FinishReason.NONE);
        }
        Map<String, ToolDefinition> definitions = definitionsByModelName(tools);
        List<ToolCall> calls = new ArrayList<>();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (int index = 0; index < toolCalls.length(); index++) {
            JSONObject toolCall = toolCalls.getJSONObject(index);
            String id = toolCall.optString("id", "");
            if (id == null || id.trim().isEmpty()) {
                throw new IllegalStateException("OpenAI 响应缺失 tool_calls[" + index + "].id,Provider 协议不一致");
            }
            if (!ids.add(id)) throw new IllegalStateException("OpenAI 响应中 tool_calls 出现重复 id=" + id);
            JSONObject function = toolCall.getJSONObject("function");
            ToolDefinition definition = requireDefinition(definitions, function.getString("name"));
            calls.add(ToolCall.withId(id, definition.getCapabilityName(), toMap(arguments(function.opt("arguments")))));
        }
        return ModelTurn.ofToolCalls(calls, content);
    }

    private static void requireTools(List<ToolDefinition> tools) {
        if (tools == null || tools.isEmpty()) throw new IllegalArgumentException("ToolDefinition 不能为空");
    }

    private static Map<String, ToolDefinition> definitionsByModelName(List<ToolDefinition> tools) {
        Map<String, ToolDefinition> definitions = new LinkedHashMap<>();
        for (ToolDefinition tool : tools) definitions.put(tool.getModelName(), tool);
        return definitions;
    }

    private static ToolDefinition requireDefinition(Map<String, ToolDefinition> definitions,
            String modelName) {
        ToolDefinition result = definitions.get(modelName);
        if (result == null) throw new IllegalStateException("OpenAI 返回未注册 Tool:" + modelName);
        return result;
    }

    private static JSONObject arguments(Object raw) throws Exception {
        if (raw instanceof JSONObject) return (JSONObject) raw;
        if (raw instanceof String && !((String) raw).trim().isEmpty()) return new JSONObject((String) raw);
        return new JSONObject();
    }

    private static JSONArray toMessages(List<AgentMessage> conversation, Map<String, String> names)
            throws Exception {
        JSONArray messages = new JSONArray();
        for (AgentMessage item : conversation) {
            switch (item.getRole()) {
                case SYSTEM:
                    break;
                case USER:
                    messages.put(message("user", item.getContent()));
                    break;
                case ASSISTANT:
                    JSONObject assistant = message("assistant", item.getContent() == null ? "" : item.getContent());
                    if (!item.getToolCalls().isEmpty()) {
                        JSONArray calls = new JSONArray();
                        for (ToolCall call : item.getToolCalls()) {
                            String name = names.containsKey(call.getCapabilityName())
                                    ? names.get(call.getCapabilityName()) : modelName(call.getCapabilityName());
                            calls.put(new JSONObject().put("id", call.getStepId()).put("type", "function")
                                    .put("function", new JSONObject().put("name", name)
                                            .put("arguments", toJson(call.getArguments()).toString())));
                        }
                        assistant.put("tool_calls", calls);
                    }
                    messages.put(assistant);
                    break;
                case TOOL:
                    JSONObject tool = message("tool", item.getContent()).put("tool_call_id", item.getToolCallId());
                    if (item.getToolName() != null && !item.getToolName().isEmpty()) {
                        tool.put("name", modelName(item.getToolName()));
                    }
                    messages.put(tool);
                    break;
            }
        }
        return messages;
    }

    private static JSONObject toTool(ToolDefinition tool) throws Exception {
        JSONObject parameters = tool.getParametersSchema() == null
                ? legacyParameters(tool)
                : SchemaJsonWriter.INSTANCE.write(tool.getParametersSchema(), SchemaProjectionConfig.OPENAI_STRICT);
        return new JSONObject().put("type", "function").put("function", new JSONObject()
                .put("name", tool.getModelName()).put("description", tool.getDescription())
                .put("parameters", parameters));
    }

    private static JSONObject legacyParameters(ToolDefinition tool) throws Exception {
        JSONObject properties = new JSONObject();
        JSONArray required = new JSONArray();
        for (ToolParameterDefinition parameter : tool.getParameters()) {
            JSONObject schema = new JSONObject().put("type", jsonType(parameter.getType()))
                    .put("description", parameter.getDescription());
            if (!parameter.getEnumValues().isEmpty()) {
                JSONArray values = new JSONArray();
                for (String value : parameter.getEnumValues()) values.put(value);
                schema.put("enum", values);
            }
            if (parameter.getMinimum() != null) schema.put("minimum", parameter.getMinimum());
            if (parameter.getMaximum() != null) schema.put("maximum", parameter.getMaximum());
            properties.put(parameter.getName(), schema);
            if (parameter.isRequired()) required.put(parameter.getName());
        }
        return new JSONObject().put("type", "object").put("properties", properties)
                .put("required", required).put("additionalProperties", false);
    }

    private static JSONObject message(String role, String content) throws Exception {
        return new JSONObject().put("role", role).put("content", content);
    }

    private static JSONObject toJson(Map<String, Object> values) throws Exception {
        JSONObject object = new JSONObject();
        if (values != null) for (Map.Entry<String, Object> entry : values.entrySet()) {
            object.put(entry.getKey(), entry.getValue() == null ? JSONObject.NULL : entry.getValue());
        }
        return object;
    }

    private static Map<String, Object> toMap(JSONObject object) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = object.get(key);
            result.put(key, value == JSONObject.NULL ? null : value);
        }
        return result;
    }

    private static String modelName(String capabilityName) {
        return capabilityName.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private static String jsonType(ToolParameterDefinition.Type type) {
        switch (type) {
            case INTEGER: return "integer";
            case NUMBER: return "number";
            case BOOLEAN: return "boolean";
            case STRING:
            default: return "string";
        }
    }

    private static FinishReason mapFinishReason(String raw) {
        if (raw == null || raw.isEmpty()) return FinishReason.NONE;
        switch (raw) {
            case "stop": return FinishReason.STOP;
            case "length": return FinishReason.LENGTH;
            case "tool_calls": return FinishReason.TOOL_CALLS;
            default: return FinishReason.NONE;
        }
    }
}
