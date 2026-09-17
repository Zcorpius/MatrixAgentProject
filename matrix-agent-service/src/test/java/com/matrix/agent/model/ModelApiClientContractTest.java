package com.matrix.agent.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.task.AgentMessage;
import com.matrix.agent.task.FinishReason;
import com.matrix.agent.task.ModelTurn;
import com.matrix.agent.task.capability.CapabilityRegistry;
import com.matrix.agent.task.capability.ToolDefinition;
import com.matrix.agent.task.tool.ToolCall;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ModelApiClientContractTest {
    private ModelConfig config;
    private List<ToolDefinition> tools;

    @Before
    public void setUp() {
        config = new ModelConfig("local", "Local", ApiProtocol.OPENAI_CHAT,
                "http://10.0.2.2:8000/v1/chat/completions", "test-model", "", false,
                PlannerMode.NATIVE_TOOL_CALLING);
        tools = CapabilityRegistry.createDemoRegistry().toToolDefinitions();
    }

    @Test
    public void nativeRequestContainsProviderNeutralToolSchema() throws Exception {
        JSONObject request = ModelApiClient.buildOpenAiToolRequest(config, "system", "user", tools);

        assertEquals("auto", request.getString("tool_choice"));
        assertEquals(10, request.getJSONArray("tools").length());
        JSONObject climate = findFunction(request.getJSONArray("tools"),
                "vehicle_climate_set_temperature");
        JSONObject parameters = climate.getJSONObject("parameters");
        assertFalse(parameters.getBoolean("additionalProperties"));
        assertEquals(2, parameters.getJSONArray("required").length());
        JSONObject temperature = parameters.getJSONObject("properties").getJSONObject("temperature");
        assertEquals("integer", temperature.getString("type"));
        assertEquals(16d, temperature.getDouble("minimum"), 0d);
        assertEquals(30d, temperature.getDouble("maximum"), 0d);
    }

    @Test
    public void nativeResponseMapsSafeFunctionNameBackToCanonicalCapability() throws Exception {
        JSONObject response = responseWithToolCall("vehicle_climate_set_temperature",
                "{\"zone\":\"driver\",\"temperature\":24}");

        ModelTurn turn = ModelApiClient.parseOpenAiToolResponse(config, response, tools);

        assertEquals(1, turn.getToolCalls().size());
        assertEquals("vehicle.climate.set_temperature", turn.getToolCalls().get(0).getCapabilityName());
        assertEquals("driver", turn.getToolCalls().get(0).argument("zone"));
        assertEquals(24, ((Number) turn.getToolCalls().get(0).argument("temperature")).intValue());
    }

    @Test(expected = IllegalStateException.class)
    public void unregisteredNativeToolIsRejected() throws Exception {
        ModelApiClient.parseOpenAiToolResponse(config,
                responseWithToolCall("vehicle_brake_apply", "{}"), tools);
    }

    @Test(expected = IllegalStateException.class)
    public void responseWithoutToolCallsDoesNotSilentlyFallback() throws Exception {
        JSONObject response = new JSONObject()
                .put("choices", new JSONArray().put(new JSONObject()
                        .put("message", new JSONObject().put("content", "普通文本回答"))));
        ModelApiClient.parseOpenAiToolResponse(config, response, tools);
    }

    @Test
    public void toolModelNamesAreUniqueAndProviderSafe() {
        java.util.Set<String> names = new java.util.HashSet<>();
        for (ToolDefinition tool : tools) {
            assertTrue(tool.getModelName().matches("[a-zA-Z0-9_-]+"));
            assertTrue("duplicate tool name: " + tool.getModelName(), names.add(tool.getModelName()));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void nativeModeRejectsUnsupportedProviderProtocol() {
        // Gemini 已支持 NATIVE_TOOL_CALLING,改用 OLLAMA_CHAT 作不支持协议。
        new ModelConfig("ollama", "Ollama", ApiProtocol.OLLAMA_CHAT,
                "http://10.0.2.2:11434/api/chat", "llama3", "", false,
                PlannerMode.NATIVE_TOOL_CALLING).validate();
    }

    @Test
    public void geminiNativeModeIsAllowedAndBuildsCanonicalRequest() throws Exception {
        // Gemini 加入 NATIVE_TOOL_CALLING 白名单。
        ModelConfig gemini = new ModelConfig("gemini", "Gemini",
                ApiProtocol.GEMINI_GENERATE_CONTENT,
                "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
                "gemini-1.5-pro", "key", true, PlannerMode.NATIVE_TOOL_CALLING);
        gemini.validate(); // 不应抛

        List<AgentMessage> conversation = new ArrayList<>();
        conversation.add(AgentMessage.system("system prompt"));
        conversation.add(AgentMessage.user("空调 24 度"));
        JSONObject request = ModelApiClient.buildGeminiToolRequest(gemini, "system prompt",
                conversation, tools);

        assertEquals("system prompt", request.getJSONObject("systemInstruction")
                .getJSONArray("parts").getJSONObject(0).getString("text"));
        JSONArray contents = request.getJSONArray("contents");
        assertEquals(1, contents.length());
        assertEquals("user", contents.getJSONObject(0).getString("role"));
        assertEquals("空调 24 度", contents.getJSONObject(0).getJSONArray("parts")
                .getJSONObject(0).getString("text"));
        // tools 是 [{functionDeclarations:[...]}]
        JSONArray functionDeclarations = request.getJSONArray("tools")
                .getJSONObject(0).getJSONArray("functionDeclarations");
        assertEquals(10, functionDeclarations.length());
    }

    @Test
    public void anthropicNativeModeIsAllowedAndBuildsCanonicalRequest() throws Exception {
        ModelConfig anthropic = new ModelConfig("anthropic", "Anthropic",
                ApiProtocol.ANTHROPIC_MESSAGES, "https://api.anthropic.com/v1/messages",
                "claude-sonnet", "key", true, PlannerMode.NATIVE_TOOL_CALLING);
        anthropic.validate();

        List<AgentMessage> conversation = new ArrayList<>();
        conversation.add(AgentMessage.system("system prompt"));
        conversation.add(AgentMessage.user("空调 24 度"));
        JSONObject request = ModelApiClient.buildAnthropicToolRequest(anthropic, "system prompt",
                conversation, tools);

        assertEquals("system prompt", request.getString("system"));
        JSONArray messages = request.getJSONArray("messages");
        assertEquals(1, messages.length());
        assertEquals("user", messages.getJSONObject(0).getString("role"));
        assertEquals("空调 24 度", messages.getJSONObject(0).getString("content"));

        JSONArray toolArray = request.getJSONArray("tools");
        JSONObject climate = findAnthropicTool(toolArray, "vehicle_climate_set_temperature");
        JSONObject inputSchema = climate.getJSONObject("input_schema");
        assertFalse(inputSchema.getBoolean("additionalProperties"));
        assertEquals(2, inputSchema.getJSONArray("required").length());
    }

    @Test
    public void anthropicNativeResponsePreservesToolUseIdAndMapsCapability() throws Exception {
        JSONObject response = new JSONObject();
        JSONArray content = new JSONArray();
        content.put(new JSONObject().put("type", "text").put("text", "正在调温度"));
        content.put(new JSONObject()
                .put("type", "tool_use")
                .put("id", "toolu_abc123")
                .put("name", "vehicle_climate_set_temperature")
                .put("input", new JSONObject().put("zone", "driver").put("temperature", 24)));
        response.put("content", content);
        // tool_use 出现时 stop_reason 必须为 tool_use,否则 PROTOCOL_ERROR
        response.put("stop_reason", "tool_use");

        ModelTurn turn = ModelApiClient.parseAnthropicToolResponse(response, tools);

        assertTrue(turn.hasToolCalls());
        assertEquals(1, turn.getToolCalls().size());
        assertEquals("toolu_abc123", turn.getToolCalls().get(0).getStepId());
        assertEquals("vehicle.climate.set_temperature", turn.getToolCalls().get(0).getCapabilityName());
        assertEquals("driver", turn.getToolCalls().get(0).argument("zone"));
        assertEquals(24, ((Number) turn.getToolCalls().get(0).argument("temperature")).intValue());
    }

    @Test
    public void anthropicNativeResponseWithoutToolUseReturnsDirectAnswer() throws Exception {
        JSONObject response = new JSONObject().put("content", new JSONArray().put(
                new JSONObject().put("type", "text").put("text", "你好")));

        ModelTurn turn = ModelApiClient.parseAnthropicToolResponse(response, tools);

        assertFalse(turn.hasToolCalls());
        assertEquals("你好", turn.getAssistantMessage().getContent());
    }

    @Test
    public void anthropicNativeConversationMergesConsecutiveToolResults() throws Exception {
        ToolCall first = new ToolCall("vehicle.climate.set_temperature",
                args("zone", "driver", "temperature", 24));
        ToolCall second = new ToolCall("vehicle.info.get_battery", args());
        List<AgentMessage> conversation = new ArrayList<>();
        conversation.add(AgentMessage.system("system"));
        conversation.add(AgentMessage.user("空调并报电量"));
        conversation.add(AgentMessage.assistant("调两个", Arrays.asList(first, second)));
        conversation.add(AgentMessage.tool(first.getStepId(),
                "vehicle.climate.set_temperature", "SUCCESS verified=true"));
        conversation.add(AgentMessage.tool(second.getStepId(),
                "vehicle.info.get_battery", "SUCCESS 78%"));

        JSONObject request = ModelApiClient.buildAnthropicToolRequest(config, "system",
                conversation, tools);
        JSONArray messages = request.getJSONArray("messages");

        // user, assistant(tool_use x2), user(tool_result x2 merged into one)
        assertEquals(3, messages.length());
        assertEquals("user", messages.getJSONObject(0).getString("role"));
        assertEquals("assistant", messages.getJSONObject(1).getString("role"));
        JSONArray assistantContent = messages.getJSONObject(1).getJSONArray("content");
        assertEquals(2, countBlocks(assistantContent, "tool_use"));

        assertEquals("user", messages.getJSONObject(2).getString("role"));
        JSONArray toolResults = messages.getJSONObject(2).getJSONArray("content");
        assertEquals(2, countBlocks(toolResults, "tool_result"));
        assertEquals(first.getStepId(),
                toolResults.getJSONObject(0).getString("tool_use_id"));
        assertEquals(second.getStepId(),
                toolResults.getJSONObject(1).getString("tool_use_id"));
    }

    // ===== OpenAI-Compatible 原生多轮 Tool Calling 契约测试 =====

    /**
     * 多轮 conversation 序列化为 OpenAI messages 时,四种 role 都要按协议正确转:
     * system 已在外层加入(本方法只跳过 conversation 里的 system),
     * user/assistant(tool_calls)/tool 各自走 OpenAI 协议规定的字段。
     *
     * <p>关键差异(与 Anthropic 路径对照):
     * <ul>
     *   <li>每个 tool 结果是独立的 role=tool message(Anthropic 是合并到同一 user);</li>
     *   <li>assistant.tool_calls[].function.arguments 必须是 JSON 字符串;</li>
     *   <li>tool message 必须带 tool_call_id(与 assistant.tool_calls[].id 一一对应)。</li>
     * </ul>
     */
    @Test
    public void openAiNativeRequestSerializesMultiTurnConversation() throws Exception {
        ToolCall first = new ToolCall("vehicle.climate.set_temperature",
                args("zone", "driver", "temperature", 24));
        ToolCall second = new ToolCall("vehicle.info.get_battery", args());
        List<AgentMessage> conversation = new ArrayList<>();
        conversation.add(AgentMessage.system("ignored-system"));
        conversation.add(AgentMessage.user("空调并报电量"));
        conversation.add(AgentMessage.assistant("调两个", Arrays.asList(first, second)));
        conversation.add(AgentMessage.tool(first.getStepId(),
                "vehicle.climate.set_temperature", "SUCCESS verified=true"));
        conversation.add(AgentMessage.tool(second.getStepId(),
                "vehicle.info.get_battery", "SUCCESS 78%"));

        JSONObject request = ModelApiClient.buildOpenAiToolRequest(config, "system",
                conversation, tools);

        // tool_choice 与 tool 数量保持原生协议
        assertEquals("auto", request.getString("tool_choice"));
        assertEquals(10, request.getJSONArray("tools").length());

        JSONArray messages = request.getJSONArray("messages");
        // system + user + assistant + tool + tool = 5 条
        assertEquals(5, messages.length());

        // 第 1 条 system(由 buildOpenAiToolRequest 单独加,不取自 conversation)
        assertEquals("system", messages.getJSONObject(0).getString("role"));
        assertEquals("system", messages.getJSONObject(0).getString("content"));

        // 第 2 条 user
        assertEquals("user", messages.getJSONObject(1).getString("role"));
        assertEquals("空调并报电量", messages.getJSONObject(1).getString("content"));

        // 第 3 条 assistant,带 tool_calls(function.arguments 必须是 string)
        JSONObject assistant = messages.getJSONObject(2);
        assertEquals("assistant", assistant.getString("role"));
        assertEquals("调两个", assistant.getString("content"));
        JSONArray assistantToolCalls = assistant.getJSONArray("tool_calls");
        assertEquals(2, assistantToolCalls.length());
        JSONObject firstToolCall = assistantToolCalls.getJSONObject(0);
        assertEquals(first.getStepId(), firstToolCall.getString("id"));
        assertEquals("function", firstToolCall.getString("type"));
        JSONObject firstFunction = firstToolCall.getJSONObject("function");
        assertEquals("vehicle_climate_set_temperature", firstFunction.getString("name"));
        // arguments 必须是 JSON string(OpenAI 协议要求)
        String argumentsStr = firstFunction.getString("arguments");
        JSONObject parsedArgs = new JSONObject(argumentsStr);
        assertEquals("driver", parsedArgs.getString("zone"));
        assertEquals(24, parsedArgs.getInt("temperature"));

        // 第 4 / 5 条 tool,每个 tool_call 独立 message,带 tool_call_id 关联
        JSONObject firstTool = messages.getJSONObject(3);
        assertEquals("tool", firstTool.getString("role"));
        assertEquals("SUCCESS verified=true", firstTool.getString("content"));
        assertEquals(first.getStepId(), firstTool.getString("tool_call_id"));

        JSONObject secondTool = messages.getJSONObject(4);
        assertEquals("tool", secondTool.getString("role"));
        assertEquals(second.getStepId(), secondTool.getString("tool_call_id"));
    }

    /** 模型返回的 tool_calls[].id 必须原样保留,下一轮才能正确回填 tool_call_id。 */
    @Test
    public void openAiNativeResponsePreservesToolCallIdAndMapsCapability() throws Exception {
        JSONObject response = openAiResponseWithToolCall("call_abc123",
                "vehicle_climate_set_temperature",
                "{\"zone\":\"driver\",\"temperature\":24}");

        ModelTurn turn = ModelApiClient.parseOpenAiToolResponse(response, tools);

        assertTrue(turn.hasToolCalls());
        assertEquals(1, turn.getToolCalls().size());
        assertEquals("call_abc123", turn.getToolCalls().get(0).getStepId());
        assertEquals("vehicle.climate.set_temperature",
                turn.getToolCalls().get(0).getCapabilityName());
        assertEquals("driver", turn.getToolCalls().get(0).argument("zone"));
        assertEquals(24, ((Number) turn.getToolCalls().get(0).argument("temperature")).intValue());
    }

    /** 同一 assistant message 多个 tool_call 必须全部解析,每个保留独立 id。 */
    @Test
    public void openAiNativeResponseSupportsMultipleToolCallsInOneMessage() throws Exception {
        JSONObject response = new JSONObject();
        JSONArray toolCalls = new JSONArray();
        toolCalls.put(openAiToolCall("call_1", "vehicle_climate_set_temperature",
                "{\"zone\":\"driver\",\"temperature\":22}"));
        toolCalls.put(openAiToolCall("call_2", "vehicle_info_get_battery", "{}"));
        JSONObject message = new JSONObject()
                .put("content", JSONObject.NULL)
                .put("tool_calls", toolCalls);
        response.put("choices", new JSONArray().put(new JSONObject()
                .put("message", message).put("finish_reason", "tool_calls")));

        ModelTurn turn = ModelApiClient.parseOpenAiToolResponse(response, tools);

        assertTrue(turn.hasToolCalls());
        assertEquals(2, turn.getToolCalls().size());
        assertEquals("call_1", turn.getToolCalls().get(0).getStepId());
        assertEquals("vehicle.climate.set_temperature",
                turn.getToolCalls().get(0).getCapabilityName());
        assertEquals("call_2", turn.getToolCalls().get(1).getStepId());
        assertEquals("vehicle.info.get_battery",
                turn.getToolCalls().get(1).getCapabilityName());
    }

    /** 无 tool_calls 时走 directAnswer,finishReason=STOP,Loop 据此终止。 */
    @Test
    public void openAiNativeResponseWithoutToolCallsReturnsDirectAnswer() throws Exception {
        JSONObject response = new JSONObject()
                .put("choices", new JSONArray().put(new JSONObject()
                        .put("message", new JSONObject().put("content", "你好,已为你处理"))
                        .put("finish_reason", "stop")));

        ModelTurn turn = ModelApiClient.parseOpenAiToolResponse(response, tools);

        assertFalse(turn.hasToolCalls());
        assertEquals("你好,已为你处理", turn.getAssistantMessage().getContent());
    }

    /** arguments 是 JSONObject 形式(部分本地 OpenAI-Compatible 服务器会这么返回),也要兼容。 */
    @Test
    public void openAiNativeResponseAcceptsObjectArguments() throws Exception {
        JSONObject function = new JSONObject()
                .put("name", "vehicle_climate_set_temperature")
                .put("arguments", new JSONObject().put("zone", "driver").put("temperature", 24));
        JSONObject toolCall = new JSONObject().put("id", "call_obj").put("type", "function")
                .put("function", function);
        JSONObject response = new JSONObject().put("choices", new JSONArray().put(
                new JSONObject().put("message", new JSONObject()
                        .put("content", JSONObject.NULL)
                        .put("tool_calls", new JSONArray().put(toolCall)))
                        .put("finish_reason", "tool_calls")));

        ModelTurn turn = ModelApiClient.parseOpenAiToolResponse(response, tools);

        assertTrue(turn.hasToolCalls());
        assertEquals(24, ((Number) turn.getToolCalls().get(0).argument("temperature")).intValue());
    }

    @Test(expected = IllegalStateException.class)
    public void openAiNativeResponseRejectsUnregisteredTool() throws Exception {
        JSONObject response = openAiResponseWithToolCall("call_x",
                "vehicle_brake_apply", "{}");
        ModelApiClient.parseOpenAiToolResponse(response, tools);
    }

    /** OpenAI 响应缺失 tool_calls[].id 必须显式失败,不能静默补 UUID。 */
    @Test(expected = IllegalStateException.class)
    public void openAiNativeResponseRejectsMissingToolCallId() throws Exception {
        JSONObject function = new JSONObject()
                .put("name", "vehicle_climate_set_temperature")
                .put("arguments", "{}");
        JSONObject toolCall = new JSONObject().put("type", "function").put("function", function);
        JSONObject response = new JSONObject().put("choices", new JSONArray().put(
                new JSONObject().put("message", new JSONObject()
                        .put("content", JSONObject.NULL)
                        .put("tool_calls", new JSONArray().put(toolCall)))
                        .put("finish_reason", "tool_calls")));
        ModelApiClient.parseOpenAiToolResponse(response, tools);
    }

    /** 同一 assistant message 中重复 id 必须显式失败。 */
    @Test(expected = IllegalStateException.class)
    public void openAiNativeResponseRejectsDuplicateToolCallIds() throws Exception {
        JSONObject toolCall1 = openAiToolCall("call_dup",
                "vehicle_climate_set_temperature", "{\"zone\":\"driver\",\"temperature\":22}");
        JSONObject toolCall2 = openAiToolCall("call_dup",
                "vehicle_info_get_battery", "{}");
        JSONObject response = new JSONObject().put("choices", new JSONArray().put(
                new JSONObject().put("message", new JSONObject()
                        .put("content", JSONObject.NULL)
                        .put("tool_calls", new JSONArray().put(toolCall1).put(toolCall2)))
                        .put("finish_reason", "tool_calls")));
        ModelApiClient.parseOpenAiToolResponse(response, tools);
    }

    /** Anthropic 响应缺失 tool_use.id 必须显式失败。 */
    @Test(expected = IllegalStateException.class)
    public void anthropicNativeResponseRejectsMissingToolUseId() throws Exception {
        JSONObject block = new JSONObject()
                .put("type", "tool_use")
                .put("name", "vehicle_climate_set_temperature")
                .put("input", new JSONObject());
        JSONObject response = new JSONObject()
                .put("stop_reason", "tool_use")
                .put("content", new JSONArray().put(block));
        ModelApiClient.parseAnthropicToolResponse(response, tools);
    }

    /** Anthropic 响应重复 tool_use.id 必须显式失败。 */
    @Test(expected = IllegalStateException.class)
    public void anthropicNativeResponseRejectsDuplicateToolUseIds() throws Exception {
        JSONArray content = new JSONArray();
        content.put(new JSONObject()
                .put("type", "tool_use")
                .put("id", "toolu_dup")
                .put("name", "vehicle_climate_set_temperature")
                .put("input", new JSONObject()));
        content.put(new JSONObject()
                .put("type", "tool_use")
                .put("id", "toolu_dup")
                .put("name", "vehicle_info_get_battery")
                .put("input", new JSONObject()));
        JSONObject response = new JSONObject()
                .put("stop_reason", "tool_use")
                .put("content", content);
        ModelApiClient.parseAnthropicToolResponse(response, tools);
    }

    /**
     * OpenAI finish_reason=length 必须映射为 FinishReason.LENGTH,
     * 而不是被错判为正常 STOP。AgentEngine 据此走 LENGTH_EXCEEDED,不会 SUCCEEDED。
     */
    @Test
    public void openAiNativeLengthFinishReasonMapsToLengthEnum() throws Exception {
        JSONObject response = new JSONObject()
                .put("choices", new JSONArray().put(new JSONObject()
                        .put("finish_reason", "length")
                        .put("message", new JSONObject().put("content", "答复被截断..."))));

        ModelTurn turn = ModelApiClient.parseOpenAiToolResponse(response, tools);

        assertFalse(turn.hasToolCalls());
        assertEquals(FinishReason.LENGTH, turn.getFinishReason());
    }

    /**
     * LENGTH 即使伴随 tool_calls 也必须短路——max_tokens 截断发生在生成过程中,
     * 无法保证 arguments JSON 完整。旧实现只在无 tool_calls 分支映射 LENGTH,
     * 导致 LENGTH + tool_calls 被强制为 TOOL_CALLS 执行半截参数。
     */
    @Test
    public void openAiNativeLengthWithToolCallsShortCircuitsBeforeExecution() throws Exception {
        JSONObject response = openAiResponseWithToolCall("call_truncated",
                "vehicle_climate_set_temperature", "{\"zone\":\"driver\",\"tempera");
        response.getJSONArray("choices").getJSONObject(0).put("finish_reason", "length");

        ModelTurn turn = ModelApiClient.parseOpenAiToolResponse(response, tools);

        assertFalse("LENGTH 必须短路,不允许执行可能被截断的 tool_calls",
                turn.hasToolCalls());
        assertEquals(FinishReason.LENGTH, turn.getFinishReason());
    }

    /**
     * Anthropic stop_reason=max_tokens 即使伴随 tool_use block 也必须短路——
     * 与 OpenAI 路径同样理由:max_tokens 截断发生时 input JSON 可能不完整。
     */
    @Test
    public void anthropicNativeMaxTokensWithToolUseShortCircuitsBeforeExecution() throws Exception {
        JSONArray content = new JSONArray();
        content.put(new JSONObject()
                .put("type", "tool_use")
                .put("id", "toolu_truncated")
                .put("name", "vehicle_climate_set_temperature")
                // 半截 input——max_tokens 截断
                .put("input", new JSONObject().put("zone", "driver")));
        JSONObject response = new JSONObject()
                .put("stop_reason", "max_tokens")
                .put("content", content);

        ModelTurn turn = ModelApiClient.parseAnthropicToolResponse(response, tools);

        assertFalse("max_tokens 必须短路,不允许执行可能被截断的 tool_use",
                turn.hasToolCalls());
        assertEquals(FinishReason.LENGTH, turn.getFinishReason());
    }

    /** OpenAI finish_reason 缺失/未知 → NONE(协议不一致,AgentEngine 不据此判定成功)。 */
    @Test
    public void openAiNativeUnknownFinishReasonMapsToNone() throws Exception {
        JSONObject response = new JSONObject()
                .put("choices", new JSONArray().put(new JSONObject()
                        .put("finish_reason", "content_filter")
                        .put("message", new JSONObject().put("content", "..."))));

        ModelTurn turn = ModelApiClient.parseOpenAiToolResponse(response, tools);

        assertEquals(FinishReason.NONE, turn.getFinishReason());
    }

    /** Anthropic stop_reason=max_tokens → LENGTH。 */
    @Test
    public void anthropicNativeMaxTokensStopReasonMapsToLength() throws Exception {
        JSONObject response = new JSONObject()
                .put("stop_reason", "max_tokens")
                .put("content", new JSONArray().put(
                        new JSONObject().put("type", "text").put("text", "答复被截断")));

        ModelTurn turn = ModelApiClient.parseAnthropicToolResponse(response, tools);

        assertFalse(turn.hasToolCalls());
        assertEquals(FinishReason.LENGTH, turn.getFinishReason());
    }

    /** Anthropic stop_reason=end_turn → STOP(正常完成)。 */
    @Test
    public void anthropicNativeEndTurnStopReasonMapsToStop() throws Exception {
        JSONObject response = new JSONObject()
                .put("stop_reason", "end_turn")
                .put("content", new JSONArray().put(
                        new JSONObject().put("type", "text").put("text", "完成")));

        ModelTurn turn = ModelApiClient.parseAnthropicToolResponse(response, tools);

        assertFalse(turn.hasToolCalls());
        assertEquals(FinishReason.STOP, turn.getFinishReason());
    }

    // ===== ToolCall 与 Provider FinishReason 一致性校验 =====

    /**
     * OpenAI 返回 tool_calls 但 finish_reason=stop → PROTOCOL_ERROR,不得执行工具。
     * 不规范本地服务或被篡改响应可能产生这种组合,核心解析器不能无条件放宽。
     */
    @Test
    public void openAiNativeToolCallsWithStopFinishReasonIsProtocolError() throws Exception {
        JSONObject response = openAiResponseWithToolCall("call_stop",
                "vehicle_climate_set_temperature",
                "{\"zone\":\"driver\",\"temperature\":24}");
        response.getJSONArray("choices").getJSONObject(0).put("finish_reason", "stop");

        ModelTurn turn = ModelApiClient.parseOpenAiToolResponse(response, tools);

        assertFalse("finish_reason=stop + tool_calls 不允许执行工具", turn.hasToolCalls());
        assertEquals(FinishReason.NONE, turn.getFinishReason());
    }

    /**
     * OpenAI 返回 tool_calls 但 finish_reason 缺失 → PROTOCOL_ERROR。
     */
    @Test
    public void openAiNativeToolCallsWithMissingFinishReasonIsProtocolError() throws Exception {
        JSONObject response = new JSONObject();
        JSONArray toolCalls = new JSONArray();
        toolCalls.put(openAiToolCall("call_missing", "vehicle_climate_set_temperature",
                "{\"zone\":\"driver\",\"temperature\":24}"));
        JSONObject message = new JSONObject()
                .put("content", JSONObject.NULL)
                .put("tool_calls", toolCalls);
        // 故意不设 finish_reason
        response.put("choices", new JSONArray().put(new JSONObject().put("message", message)));

        ModelTurn turn = ModelApiClient.parseOpenAiToolResponse(response, tools);

        assertFalse("缺失 finish_reason + tool_calls 不允许执行工具", turn.hasToolCalls());
        assertEquals(FinishReason.NONE, turn.getFinishReason());
    }

    /**
     * OpenAI 返回 tool_calls 但 finish_reason=未知值(如 content_filter)→ PROTOCOL_ERROR。
     */
    @Test
    public void openAiNativeToolCallsWithUnknownFinishReasonIsProtocolError() throws Exception {
        JSONObject response = openAiResponseWithToolCall("call_unknown",
                "vehicle_climate_set_temperature",
                "{\"zone\":\"driver\",\"temperature\":24}");
        response.getJSONArray("choices").getJSONObject(0).put("finish_reason", "content_filter");

        ModelTurn turn = ModelApiClient.parseOpenAiToolResponse(response, tools);

        assertFalse("未知 finish_reason + tool_calls 不允许执行工具", turn.hasToolCalls());
        assertEquals(FinishReason.NONE, turn.getFinishReason());
    }

    /**
     * Anthropic 返回 tool_use 但 stop_reason=end_turn → PROTOCOL_ERROR。
     */
    @Test
    public void anthropicNativeToolUseWithEndTurnStopReasonIsProtocolError() throws Exception {
        JSONArray content = new JSONArray();
        content.put(new JSONObject()
                .put("type", "tool_use")
                .put("id", "toolu_end_turn")
                .put("name", "vehicle_climate_set_temperature")
                .put("input", new JSONObject().put("zone", "driver").put("temperature", 24)));
        JSONObject response = new JSONObject()
                .put("stop_reason", "end_turn")
                .put("content", content);

        ModelTurn turn = ModelApiClient.parseAnthropicToolResponse(response, tools);

        assertFalse("stop_reason=end_turn + tool_use 不允许执行工具", turn.hasToolCalls());
        assertEquals(FinishReason.NONE, turn.getFinishReason());
    }

    /**
     * Anthropic 返回 tool_use 但 stop_reason 缺失 → PROTOCOL_ERROR。
     */
    @Test
    public void anthropicNativeToolUseWithMissingStopReasonIsProtocolError() throws Exception {
        JSONArray content = new JSONArray();
        content.put(new JSONObject()
                .put("type", "tool_use")
                .put("id", "toolu_missing")
                .put("name", "vehicle_climate_set_temperature")
                .put("input", new JSONObject().put("zone", "driver").put("temperature", 24)));
        JSONObject response = new JSONObject().put("content", content);

        ModelTurn turn = ModelApiClient.parseAnthropicToolResponse(response, tools);

        assertFalse("缺失 stop_reason + tool_use 不允许执行工具", turn.hasToolCalls());
        assertEquals(FinishReason.NONE, turn.getFinishReason());
    }

    private static JSONObject findFunction(JSONArray tools, String name) throws Exception {
        for (int index = 0; index < tools.length(); index++) {
            JSONObject function = tools.getJSONObject(index).getJSONObject("function");
            if (name.equals(function.getString("name"))) return function;
        }
        throw new AssertionError("Missing function: " + name);
    }

    private static JSONObject findAnthropicTool(JSONArray tools, String name) throws Exception {
        for (int i = 0; i < tools.length(); i++) {
            JSONObject tool = tools.getJSONObject(i);
            if (name.equals(tool.getString("name"))) return tool;
        }
        throw new AssertionError("Missing tool: " + name);
    }

    private static int countBlocks(JSONArray content, String type) throws Exception {
        int count = 0;
        for (int i = 0; i < content.length(); i++) {
            if (type.equals(content.getJSONObject(i).optString("type"))) count++;
        }
        return count;
    }

    private static JSONObject responseWithToolCall(String name, String arguments) throws Exception {
        JSONObject function = new JSONObject().put("name", name).put("arguments", arguments);
        JSONObject toolCall = new JSONObject().put("id", "call_1").put("type", "function")
                .put("function", function);
        JSONObject message = new JSONObject().put("content", JSONObject.NULL)
                .put("tool_calls", new JSONArray().put(toolCall));
        return new JSONObject().put("choices", new JSONArray().put(
                new JSONObject().put("message", message)));
    }

    private static JSONObject openAiToolCall(String id, String modelName, String arguments)
            throws Exception {
        JSONObject function = new JSONObject().put("name", modelName).put("arguments", arguments);
        return new JSONObject().put("id", id).put("type", "function").put("function", function);
    }

    /**
     * OpenAI 协议规定返回 tool_calls 时 finish_reason 必须为 "tool_calls"。
     * 测试辅助方法默认带上正确 finish_reason;需要测试不一致组合的用例显式覆盖。
     */
    private static JSONObject openAiResponseWithToolCall(String id, String modelName,
            String arguments) throws Exception {
        JSONObject message = new JSONObject().put("content", JSONObject.NULL)
                .put("tool_calls", new JSONArray().put(openAiToolCall(id, modelName, arguments)));
        return new JSONObject().put("choices", new JSONArray().put(
                new JSONObject().put("message", message).put("finish_reason", "tool_calls")));
    }

    private static Map<String, Object> args(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            result.put(String.valueOf(values[i]), values[i + 1]);
        }
        return result;
    }
}
