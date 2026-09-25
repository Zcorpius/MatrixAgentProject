package com.matrix.agent.model;

import com.matrix.agent.contract.PlannerMode;

import com.matrix.agent.contract.ModelConfig;

import com.matrix.agent.contract.ApiProtocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.contract.AgentMessage;
import com.matrix.agent.contract.FinishReason;
import com.matrix.agent.contract.ModelTurn;
import com.matrix.agent.task.capability.CapabilityRegistry;
import com.matrix.agent.contract.ToolDefinition;
import com.matrix.agent.contract.ToolCall;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Gemini 原生 Tool Calling 请求构造 / 响应解析 / 多轮 conversation 序列化。
 *
 * <p>覆盖:
 * <ul>
 *   <li>buildGeminiToolRequest:systemInstruction / contents / tools[].functionDeclarations /
 *       generationConfig 结构;</li>
 *   <li>parseGeminiToolResponse:text-only 直接答复 / functionCall 工具调用 / MAX_TOKENS 截断 /
 *       未注册 Tool / STOP + 空内容降级 NONE;</li>
 *   <li>多轮 conversation 序列化:ASSISTANT.tool_calls → model.parts[functionCall],
 *       TOOL 消息合并到下一条 user.parts[functionResponse]。</li>
 * </ul>
 */
public final class GeminiNativeToolCallingTest {
    private ModelConfig gemini;
    private List<ToolDefinition> tools;

    @Before
    public void setUp() {
        gemini = new ModelConfig("gemini", "Gemini",
                ApiProtocol.GEMINI_GENERATE_CONTENT,
                "https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
                "gemini-1.5-pro", "key", true, PlannerMode.NATIVE_TOOL_CALLING);
        tools = CapabilityRegistry.createDemoRegistry().toToolDefinitions();
    }

    @Test
    public void buildRequestHasGeminiCanonicalShape() throws Exception {
        JSONObject request = ModelApiClient.buildGeminiToolRequest(gemini, "system prompt",
                Collections.singletonList(AgentMessage.user("hello")), tools);

        // systemInstruction.parts[0].text
        assertEquals("system prompt", request.getJSONObject("systemInstruction")
                .getJSONArray("parts").getJSONObject(0).getString("text"));
        // contents[0].role = user, parts[0].text = hello
        JSONArray contents = request.getJSONArray("contents");
        assertEquals(1, contents.length());
        assertEquals("user", contents.getJSONObject(0).getString("role"));
        assertEquals("hello", contents.getJSONObject(0).getJSONArray("parts")
                .getJSONObject(0).getString("text"));
        // tools 是 [{functionDeclarations:[...]}],不是 OpenAI 平铺
        JSONArray functionDeclarations = request.getJSONArray("tools")
                .getJSONObject(0).getJSONArray("functionDeclarations");
        assertEquals(17, functionDeclarations.length());
        JSONObject climate = findFunctionDeclaration(functionDeclarations,
                "vehicle_climate_set_temperature");
        assertEquals("vehicle_climate_set_temperature", climate.getString("name"));
        // parameters 必须存在(用 OPENAI_STRICT 投影)
        assertNotNull(climate.getJSONObject("parameters"));
        // temperature 在 generationConfig 内,不在顶层
        assertEquals(0.1, request.getJSONObject("generationConfig").getDouble("temperature"), 0.0001);
    }

    @Test
    public void buildRequestDropsSystemMessagesFromConversation() throws Exception {
        List<AgentMessage> conversation = new ArrayList<>();
        conversation.add(AgentMessage.system("ignored-system-msg"));  // 应被丢弃
        conversation.add(AgentMessage.user("hello"));
        JSONObject request = ModelApiClient.buildGeminiToolRequest(gemini, "actual-system",
                conversation, tools);
        JSONArray contents = request.getJSONArray("contents");
        assertEquals(1, contents.length());
        assertEquals("hello", contents.getJSONObject(0).getJSONArray("parts")
                .getJSONObject(0).getString("text"));
        // systemInstruction 用的是显式 system 参数,不是 conversation 里的 system 消息
        assertEquals("actual-system", request.getJSONObject("systemInstruction")
                .getJSONArray("parts").getJSONObject(0).getString("text"));
    }

    @Test
    public void parseTextOnlyResponseReturnsDirectAnswer() throws Exception {
        JSONObject response = geminiResponse("STOP",
                new JSONObject().put("text", "你好,我是助手"));
        ModelTurn turn = ModelApiClient.parseGeminiToolResponse(response, tools);

        assertFalse(turn.hasToolCalls());
        assertEquals(FinishReason.STOP, turn.getFinishReason());
        assertEquals("你好,我是助手", turn.getAssistantMessage().getContent());
    }

    @Test
    public void parseFunctionCallResponseSynthesizesIdAndMapsCapability() throws Exception {
        JSONObject response = geminiResponse("STOP",
                new JSONObject().put("functionCall", new JSONObject()
                        .put("name", "vehicle_climate_set_temperature")
                        .put("args", new JSONObject()
                                .put("zone", "driver")
                                .put("temperature", 24))));
        ModelTurn turn = ModelApiClient.parseGeminiToolResponse(response, tools);

        assertTrue(turn.hasToolCalls());
        assertEquals(FinishReason.TOOL_CALLS, turn.getFinishReason());
        assertEquals(1, turn.getToolCalls().size());
        ToolCall call = turn.getToolCalls().get(0);
        assertEquals("vehicle.climate.set_temperature", call.getCapabilityName());
        assertEquals("driver", call.argument("zone"));
        assertEquals(24, ((Number) call.argument("temperature")).intValue());
        // Gemini 协议无 id,Runtime 合成 gemini-<index> 仅作内部关联
        assertNotNull(call.getStepId());
        assertTrue("合成的 stepId 应以 gemini- 开头,实际:" + call.getStepId(),
                call.getStepId().startsWith("gemini-"));
    }

    @Test
    public void parseResponseWithTextAndFunctionCall() throws Exception {
        JSONObject response = geminiResponse("STOP",
                new JSONObject().put("text", "正在调温度"),
                new JSONObject().put("functionCall", new JSONObject()
                        .put("name", "vehicle_climate_set_temperature")
                        .put("args", new JSONObject()
                                .put("zone", "driver").put("temperature", 22))));
        ModelTurn turn = ModelApiClient.parseGeminiToolResponse(response, tools);

        assertTrue(turn.hasToolCalls());
        assertEquals(1, turn.getToolCalls().size());
        assertEquals("正在调温度", turn.getAssistantMessage().getContent());
        assertEquals("vehicle.climate.set_temperature",
                turn.getToolCalls().get(0).getCapabilityName());
    }

    @Test(expected = IllegalStateException.class)
    public void unregisteredFunctionCallIsRejected() throws Exception {
        JSONObject response = geminiResponse("STOP",
                new JSONObject().put("functionCall", new JSONObject()
                        .put("name", "vehicle_brake_apply")
                        .put("args", new JSONObject())));
        ModelApiClient.parseGeminiToolResponse(response, tools);
    }

    @Test
    public void maxTokensShortCircuitBeforeExecutingFunctionCall() throws Exception {
        // MAX_TOKENS 截断可能产生半截 args JSON,必须拒绝执行
        JSONObject response = geminiResponse("MAX_TOKENS",
                new JSONObject().put("functionCall", new JSONObject()
                        .put("name", "vehicle_climate_set_temperature")
                        .put("args", new JSONObject()
                                .put("zone", "driver") /* 缺 temperature */)));
        ModelTurn turn = ModelApiClient.parseGeminiToolResponse(response, tools);

        assertFalse("MAX_TOKENS 截断时不应执行 functionCall", turn.hasToolCalls());
        assertEquals(FinishReason.LENGTH, turn.getFinishReason());
    }

    @Test
    public void stopWithEmptyContentDegradesToNone() throws Exception {
        JSONObject response = new JSONObject().put("candidates", new JSONArray().put(
                new JSONObject()
                        .put("content", new JSONObject().put("parts", new JSONArray()))
                        .put("finishReason", "STOP")));
        ModelTurn turn = ModelApiClient.parseGeminiToolResponse(response, tools);

        assertFalse(turn.hasToolCalls());
        assertEquals("STOP + 空 content -> NONE", FinishReason.NONE, turn.getFinishReason());
    }

    @Test
    public void missingCandidatesReturnsNone() throws Exception {
        JSONObject response = new JSONObject();  // 没有 candidates
        ModelTurn turn = ModelApiClient.parseGeminiToolResponse(response, tools);

        assertFalse(turn.hasToolCalls());
        assertEquals(FinishReason.NONE, turn.getFinishReason());
    }

    @Test
    public void multiTurnConversationSerializesAssistantToolCallsAndToolResults() throws Exception {
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

        JSONObject request = ModelApiClient.buildGeminiToolRequest(gemini, "system",
                conversation, tools);
        JSONArray contents = request.getJSONArray("contents");

        // user(text) + model(text + functionCall x2) + user(functionResponse x2 merged)
        assertEquals(3, contents.length());
        assertEquals("user", contents.getJSONObject(0).getString("role"));
        assertEquals("model", contents.getJSONObject(1).getString("role"));
        assertEquals("user", contents.getJSONObject(2).getString("role"));

        // model 消息:parts[0]=text, parts[1..2]=functionCall
        JSONArray modelParts = contents.getJSONObject(1).getJSONArray("parts");
        assertEquals("调两个", modelParts.getJSONObject(0).getString("text"));
        JSONObject firstCall = modelParts.getJSONObject(1).getJSONObject("functionCall");
        assertEquals("vehicle_climate_set_temperature", firstCall.getString("name"));
        assertEquals("driver", firstCall.getJSONObject("args").getString("zone"));
        JSONObject secondCall = modelParts.getJSONObject(2).getJSONObject("functionCall");
        assertEquals("vehicle_info_get_battery", secondCall.getString("name"));

        // user 消息合并 2 个 functionResponse
        JSONArray userParts = contents.getJSONObject(2).getJSONArray("parts");
        assertEquals(2, userParts.length());
        assertEquals("vehicle_climate_set_temperature",
                userParts.getJSONObject(0).getJSONObject("functionResponse").getString("name"));
        assertEquals("SUCCESS verified=true",
                userParts.getJSONObject(0).getJSONObject("functionResponse")
                        .getJSONObject("response").getString("content"));
        assertEquals("vehicle_info_get_battery",
                userParts.getJSONObject(1).getJSONObject("functionResponse").getString("name"));
    }

    @Test
    public void multiTurnWithUnmergedUserMessageAfterToolResult() throws Exception {
        // functionResponse 必须在遇到下一条 USER 消息前 flush,避免错序
        ToolCall call = new ToolCall("vehicle.climate.set_temperature",
                args("zone", "driver", "temperature", 24));
        List<AgentMessage> conversation = new ArrayList<>();
        conversation.add(AgentMessage.user("空调 24 度"));
        conversation.add(AgentMessage.assistant("调温度", Collections.singletonList(call)));
        conversation.add(AgentMessage.tool(call.getStepId(),
                "vehicle.climate.set_temperature", "SUCCESS"));
        conversation.add(AgentMessage.user("再调到 26 度"));

        JSONObject request = ModelApiClient.buildGeminiToolRequest(gemini, "system",
                conversation, tools);
        JSONArray contents = request.getJSONArray("contents");

        // user(text) + model(functionCall) + user(functionResponse) + user(text)
        assertEquals(4, contents.length());
        assertEquals("user", contents.getJSONObject(0).getString("role"));
        assertEquals("model", contents.getJSONObject(1).getString("role"));
        assertEquals("user", contents.getJSONObject(2).getString("role"));
        // 第 2 个 user 的 parts 必须只有 1 个 functionResponse(没把后续 text 合并进来)
        JSONArray toolUserParts = contents.getJSONObject(2).getJSONArray("parts");
        assertEquals(1, toolUserParts.length());
        assertTrue(toolUserParts.getJSONObject(0).has("functionResponse"));
        // 第 3 个 user 是独立的 text 消息
        JSONArray nextUserParts = contents.getJSONObject(3).getJSONArray("parts");
        assertEquals(1, nextUserParts.length());
        assertEquals("再调到 26 度", nextUserParts.getJSONObject(0).getString("text"));
    }

    private static JSONObject findFunctionDeclaration(JSONArray arr, String name) throws Exception {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.getJSONObject(i);
            if (name.equals(obj.getString("name"))) return obj;
        }
        throw new AssertionError("functionDeclaration not found: " + name);
    }

    private static JSONObject geminiResponse(String finishReason, JSONObject... parts)
            throws Exception {
        JSONArray partsArray = new JSONArray();
        for (JSONObject p : parts) partsArray.put(p);
        return new JSONObject().put("candidates", new JSONArray().put(
                new JSONObject()
                        .put("content", new JSONObject()
                                .put("role", "model")
                                .put("parts", partsArray))
                        .put("finishReason", finishReason)));
    }

    private static java.util.Map<String, Object> args(Object... kv) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
