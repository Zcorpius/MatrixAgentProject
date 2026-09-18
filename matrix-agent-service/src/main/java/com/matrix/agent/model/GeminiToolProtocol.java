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

/** Gemini generateContent native tool-calling codec; no retry, endpoint or HTTP policy. */
final class GeminiToolProtocol {
    private static final String TAG = "MatrixAgent";

    static JSONObject buildRequest(ModelConfig config, String system, List<AgentMessage> conversation,
            List<ToolDefinition> tools) throws Exception {
        if (tools == null || tools.isEmpty()) throw new IllegalArgumentException("ToolDefinition 不能为空");
        JSONArray declarations = new JSONArray();
        Map<String, String> names = new LinkedHashMap<>();
        for (ToolDefinition tool : tools) {
            declarations.put(toDeclaration(tool));
            names.put(tool.getCapabilityName(), tool.getModelName());
        }
        return new JSONObject().put("systemInstruction", new JSONObject().put("parts",
                new JSONArray().put(new JSONObject().put("text", system))))
                .put("contents", toContents(conversation, names))
                .put("tools", new JSONArray().put(new JSONObject().put("functionDeclarations", declarations)))
                .put("generationConfig", new JSONObject().put("temperature", 0.1));
    }

    static ModelTurn parseResponse(JSONObject response, List<ToolDefinition> tools) throws Exception {
        JSONArray candidates = response.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) return ModelTurn.of("", FinishReason.NONE);
        JSONObject candidate = candidates.getJSONObject(0);
        String rawFinish = candidate.optString("finishReason", "");
        JSONObject content = candidate.optJSONObject("content");
        JSONArray parts = content == null ? null : content.optJSONArray("parts");
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new java.util.ArrayList<>();
        Map<String, ToolDefinition> definitions = new LinkedHashMap<>();
        for (ToolDefinition tool : tools) definitions.put(tool.getModelName(), tool);
        if (parts != null) for (int index = 0; index < parts.length(); index++) {
            JSONObject part = parts.getJSONObject(index);
            String partText = part.optString("text", "");
            if (!partText.isEmpty()) { text.append(partText); continue; }
            JSONObject functionCall = part.optJSONObject("functionCall");
            if (functionCall == null) continue;
            String name = functionCall.optString("name", "");
            ToolDefinition definition = definitions.get(name);
            if (definition == null) throw new IllegalStateException("Gemini 返回未注册 Tool:" + name);
            Object args = functionCall.opt("args");
            calls.add(ToolCall.withId("gemini-" + index, definition.getCapabilityName(),
                    args instanceof JSONObject ? toMap((JSONObject) args) : new LinkedHashMap<>()));
        }
        Log.d(TAG, "[Http] gemini-native parse parts=" + (parts == null ? 0 : parts.length())
                + " functionCalls=" + calls.size() + " finishReason=" + rawFinish);
        FinishReason reason = mapFinishReason(rawFinish);
        if (reason == FinishReason.LENGTH) return ModelTurn.of(text.toString(), FinishReason.LENGTH);
        if (calls.isEmpty()) {
            if (reason == FinishReason.STOP && text.toString().trim().isEmpty()) reason = FinishReason.NONE;
            return ModelTurn.of(text.toString(), reason);
        }
        return ModelTurn.ofToolCalls(calls, text.toString());
    }

    private static JSONArray toContents(List<AgentMessage> conversation, Map<String, String> names)
            throws Exception {
        JSONArray contents = new JSONArray(); JSONObject pendingResponses = null;
        for (AgentMessage item : conversation) {
            switch (item.getRole()) {
                case SYSTEM: break;
                case USER:
                    if (pendingResponses != null) { contents.put(pendingResponses); pendingResponses = null; }
                    contents.put(new JSONObject().put("role", "user").put("parts", new JSONArray()
                            .put(new JSONObject().put("text", item.getContent()))));
                    break;
                case ASSISTANT:
                    if (pendingResponses != null) { contents.put(pendingResponses); pendingResponses = null; }
                    JSONArray parts = new JSONArray();
                    if (item.getContent() != null && !item.getContent().isEmpty())
                        parts.put(new JSONObject().put("text", item.getContent()));
                    for (ToolCall call : item.getToolCalls()) {
                        String name = names.containsKey(call.getCapabilityName())
                                ? names.get(call.getCapabilityName()) : modelName(call.getCapabilityName());
                        parts.put(new JSONObject().put("functionCall", new JSONObject().put("name", name)
                                .put("args", toJson(call.getArguments()))));
                    }
                    if (parts.length() == 0) parts.put(new JSONObject().put("text", ""));
                    contents.put(new JSONObject().put("role", "model").put("parts", parts));
                    break;
                case TOOL:
                    if (pendingResponses == null) pendingResponses = new JSONObject().put("role", "user")
                            .put("parts", new JSONArray());
                    String name = names.containsKey(item.getToolName()) ? names.get(item.getToolName())
                            : modelName(item.getToolName());
                    pendingResponses.getJSONArray("parts").put(new JSONObject().put("functionResponse",
                            new JSONObject().put("name", name).put("response", new JSONObject()
                                    .put("content", item.getContent()))));
                    break;
            }
        }
        if (pendingResponses != null) contents.put(pendingResponses);
        return contents;
    }

    private static JSONObject toDeclaration(ToolDefinition tool) throws Exception {
        JSONObject schema = tool.getParametersSchema() == null ? legacySchema(tool)
                : SchemaJsonWriter.INSTANCE.write(tool.getParametersSchema(), SchemaProjectionConfig.OPENAI_STRICT);
        return new JSONObject().put("name", tool.getModelName()).put("description", tool.getDescription())
                .put("parameters", schema);
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

    private static Map<String, Object> toMap(JSONObject object) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>(); java.util.Iterator<String> keys = object.keys();
        while (keys.hasNext()) { String key = keys.next(); Object value = object.get(key);
            result.put(key, value == JSONObject.NULL ? null : value); }
        return result;
    }
    private static JSONObject toJson(Map<String, Object> values) throws Exception {
        JSONObject result = new JSONObject(); if (values != null) for (Map.Entry<String, Object> item : values.entrySet())
            result.put(item.getKey(), item.getValue() == null ? JSONObject.NULL : item.getValue());
        return result;
    }
    private static String modelName(String name) { return name.replaceAll("[^a-zA-Z0-9_-]", "_"); }
    private static String jsonType(ToolParameterDefinition.Type type) { switch (type) {
        case INTEGER: return "integer"; case NUMBER: return "number"; case BOOLEAN: return "boolean";
        case STRING: default: return "string"; } }
    private static FinishReason mapFinishReason(String raw) {
        if (raw == null || raw.isEmpty()) return FinishReason.NONE;
        switch (raw) { case "STOP": return FinishReason.STOP; case "MAX_TOKENS": return FinishReason.LENGTH;
            default: return FinishReason.NONE; }
    }
}
