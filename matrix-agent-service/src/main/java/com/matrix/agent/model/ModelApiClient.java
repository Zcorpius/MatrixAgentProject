package com.matrix.agent.model;

import android.util.Log;

import com.matrix.agent.task.AgentMessage;
import com.matrix.agent.task.ModelTurn;
import com.matrix.agent.task.capability.ToolDefinition;
import com.matrix.agent.task.identity.CancellationToken;
import com.matrix.agent.platform.MatrixHttpClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * 多 Provider LLM HTTP 客户端——OpenAI / Anthropic / Gemini / Ollama 四协议的原生 Tool Calling
 * 适配器(请求构造 + 响应解析 + 消息映射)。连接、取消、错误映射由
 * {@link JsonHttpTransport} 统一处理。
 */
public final class ModelApiClient implements LlmClient {
    private static final String TAG = "MatrixAgent";
    private final JsonHttpTransport httpTransport;

    /**
     * 重试策略——对 RateLimitException(429)/ ServerException(5xx)
     * 指数退避重试 3 次;ClientException(4xx)/ NetworkException / TimeoutException 不重试。
     *
     * <p>主路径 complete / callAnthropicWithTools / callOpenAiWithTools / callGemini
     * 通过 {@link #invokeWithRetry(RetryPolicy.CallableWithRetry)} 包装。
     */
    private final RetryPolicy retryPolicy = new RetryPolicy();

    /** Host composition injects its process-owned transport. */
    public ModelApiClient(okhttp3.OkHttpClient client) {
        if (client == null) throw new IllegalArgumentException("client 不能为空");
        httpTransport = new JsonHttpTransport(client);
    }

    /** Convenience factory for isolated JVM tests; production must receive the Host-owned client. */
    public static ModelApiClient forTesting() {
        return new ModelApiClient(new MatrixHttpClient().provider());
    }

    /** 测试可见——暴露当前 RetryPolicy(只读,不替换)。 */
    RetryPolicy getRetryPolicy() {
        return retryPolicy;
    }

    /**
     * 统一重试入口——业务调用包装在 lambda 中,RetryPolicy 自动重试。
     *
     * <p>旧重载转发到新 cancel+deadline 重载,默认 token=null + 不限 deadline。
     */
    private <T> T invokeWithRetry(RetryPolicy.CallableWithRetry<T> action) throws Exception {
        return invokeWithRetry(action, null, Long.MAX_VALUE);
    }

    /**
     * cancel + deadline 感知的重试入口——直接转发给 RetryPolicy 新重载。
     */
    private <T> T invokeWithRetry(RetryPolicy.CallableWithRetry<T> action, CancellationToken token,
            long deadlineAtMillis) throws Exception {
        return retryPolicy.invokeWithRetry(action, token, deadlineAtMillis);
    }

    @Override
    public String complete(ModelConfig config, String systemPrompt, String userPrompt) throws Exception {
        return complete(config, systemPrompt, userPrompt, null, Long.MAX_VALUE);
    }

    /**
     * cancel + deadline 感知的 complete 重载。
     *
     * <p>LlmModelGateway 从 AgentRequest 取 token+deadline 透传过来;LlmIntentClassifier 用 3s 短 deadline;
     * LlmSummaryProvider 用 min(10s, request.remainingMillis())。
     */
    @Override
    public String complete(ModelConfig config, String systemPrompt, String userPrompt,
            CancellationToken token, long deadlineAtMillis) throws Exception {
        config.validate();
        Log.d(TAG, "[Http] complete protocol=" + config.protocol
                + " model=" + config.model + " systemChars=" + systemPrompt.length()
                + " userChars=" + userPrompt.length()
                + " abortable=" + (token != null)
                + " deadlineMs=" + (deadlineAtMillis == Long.MAX_VALUE ? "none" : deadlineAtMillis));
        // 走 RetryPolicy 新重载——退避期间感知 cancel + 按剩余 deadline 截断 delay。
        return invokeWithRetry(() -> {
            switch (config.protocol) {
                case ANTHROPIC_MESSAGES:
                    return callAnthropic(config, systemPrompt, userPrompt);
                case GEMINI_GENERATE_CONTENT:
                    return callGemini(config, systemPrompt, userPrompt);
                case OLLAMA_CHAT:
                    return callOllama(config, systemPrompt, userPrompt);
                case OPENAI_CHAT:
                default:
                    return callOpenAiCompatible(config, systemPrompt, userPrompt);
            }
        }, token, deadlineAtMillis);
    }

    /** Test-visible one-turn OpenAI wire request builder. */
    static JSONObject buildOpenAiToolRequest(ModelConfig config, String system, String user,
            List<ToolDefinition> tools) throws Exception {
        return OpenAiToolProtocol.buildSingleTurnRequest(config, system, user, tools);
    }

    /** Test-visible one-turn OpenAI response normalizer. */
    static ModelTurn parseOpenAiToolResponse(ModelConfig config, JSONObject response,
            List<ToolDefinition> tools) throws Exception {
        return OpenAiToolProtocol.parseSingleTurnResponse(config, response, tools);
    }

    /**
     * Anthropic 原生 Tool Calling。每轮把完整 conversation(含上轮 tool_use / tool_result)
     * 一次性发出,Anthropic 自行决定本轮还要不要再调 tool。返回 {@link ModelTurn} 供 Loop 拼回。
     */
    public ModelTurn callAnthropicWithTools(ModelConfig config, String system,
            java.util.List<AgentMessage> conversation, java.util.List<ToolDefinition> tools)
            throws Exception {
        return callAnthropicWithTools(config, system, conversation, tools, null);
    }

    /**
     * 接 {@link CancellationToken}——cancel() 时通过 abortHook 触发
     * connection.disconnect(),无需等待 read timeout(90s)。
     */
    public ModelTurn callAnthropicWithTools(ModelConfig config, String system,
            java.util.List<AgentMessage> conversation, java.util.List<ToolDefinition> tools,
            com.matrix.agent.task.identity.CancellationToken token) throws Exception {
        return callAnthropicWithTools(config, system, conversation, tools, token, Long.MAX_VALUE);
    }

    /**
     * cancel + deadline 感知的 Anthropic 原生 Tool Calling。
     *
     * <p>从 AgentRequest.deadlineAtMillis 透传——RetryPolicy 退避期间按剩余 deadline 截断 delay。
     */
    public ModelTurn callAnthropicWithTools(ModelConfig config, String system,
            java.util.List<AgentMessage> conversation, java.util.List<ToolDefinition> tools,
            com.matrix.agent.task.identity.CancellationToken token, long deadlineAtMillis) throws Exception {
        config.validate();
        if (config.protocol != ApiProtocol.ANTHROPIC_MESSAGES) {
            throw new IllegalArgumentException("Anthropic Native Tool Calling 仅支持 ANTHROPIC_MESSAGES 协议");
        }
        Log.i(TAG, "[Http] callAnthropicWithTools model=" + config.model
                + " conversationMsgs=" + conversation.size()
                + " tools=" + tools.size() + " systemChars=" + system.length()
                + " abortable=" + (token != null)
                + " deadlineMs=" + (deadlineAtMillis == Long.MAX_VALUE ? "none" : deadlineAtMillis));
        // 走 RetryPolicy 新重载——退避期间感知 cancel + 按剩余 deadline 截断 delay。
        return invokeWithRetry(() -> {
            JSONObject request = buildAnthropicToolRequest(config, system, conversation, tools);
            JSONObject response = post(config.endpoint, request, "x-api-key", config.apiKey,
                    "anthropic-version", "2023-06-01", token);
            ModelTurn turn = parseAnthropicToolResponse(response, tools);
            Log.d(TAG, "[Http] anthropic turn hasToolCalls=" + turn.hasToolCalls()
                    + " toolCalls=" + (turn.hasToolCalls() ? turn.getToolCalls().size() : 0));
            return turn;
        }, token, deadlineAtMillis);
    }

    /**
     * OpenAI-Compatible 原生多轮 Tool Calling。每轮把完整 conversation(含 assistant.tool_calls
     * 与 role=tool 的 tool_call_id 关联)一次性发出,服务器自行决定本轮还要不要再调 tool。
     * 返回 {@link ModelTurn} 供 Loop 拼回。
     *
     * <p>与 Anthropic 路径({@link #callAnthropicWithTools})的差异:
     * <ul>
     *   <li>tool 结果用 role=tool 的独立 message,每个 tool_call 一条(Anthropic 是合并到
     *       单 user message 的多个 tool_result block);</li>
     *   <li>assistant.tool_calls[].id 必须原样回传到 tool.tool_call_id(模型生成,Runtime 透传,
     *       与 Anthropic tool_use.id 同样不允许 Runtime 自造);</li>
     *   <li>function.arguments 是 JSON 字符串,不是对象(部分本地服务器会返回对象,parse 路径做兼容);</li>
     *   <li>content 在有 tool_calls 时可以为 null/空(本地服务器更兼容空字符串)。</li>
     * </ul>
     */
    public ModelTurn callOpenAiWithTools(ModelConfig config, String system,
            java.util.List<AgentMessage> conversation, java.util.List<ToolDefinition> tools)
            throws Exception {
        return callOpenAiWithTools(config, system, conversation, tools, null);
    }

    /**
     * 接 {@link CancellationToken}——cancel() 时通过 abortHook 触发
     * connection.disconnect(),无需等待 read timeout(90s)。
     */
    public ModelTurn callOpenAiWithTools(ModelConfig config, String system,
            java.util.List<AgentMessage> conversation, java.util.List<ToolDefinition> tools,
            com.matrix.agent.task.identity.CancellationToken token) throws Exception {
        return callOpenAiWithTools(config, system, conversation, tools, token, Long.MAX_VALUE);
    }

    /**
     * cancel + deadline 感知的 OpenAI-Compatible 原生 Tool Calling。
     */
    public ModelTurn callOpenAiWithTools(ModelConfig config, String system,
            java.util.List<AgentMessage> conversation, java.util.List<ToolDefinition> tools,
            com.matrix.agent.task.identity.CancellationToken token, long deadlineAtMillis) throws Exception {
        config.validate();
        if (config.protocol != ApiProtocol.OPENAI_CHAT) {
            throw new IllegalArgumentException(
                    "OpenAI Native Tool Calling 仅支持 OPENAI_CHAT 协议");
        }
        Log.i(TAG, "[Http] callOpenAiWithTools model=" + config.model
                + " conversationMsgs=" + conversation.size()
                + " tools=" + tools.size() + " systemChars=" + system.length()
                + " abortable=" + (token != null)
                + " deadlineMs=" + (deadlineAtMillis == Long.MAX_VALUE ? "none" : deadlineAtMillis));
        // 走 RetryPolicy 新重载——退避期间感知 cancel + 按剩余 deadline 截断 delay。
        return invokeWithRetry(() -> {
            JSONObject request = buildOpenAiToolRequest(config, system, conversation, tools);
            JSONObject response = post(config.endpoint, request, "Authorization",
                    config.apiKey.isEmpty() ? null : "Bearer " + config.apiKey, null, null, token);
            ModelTurn turn = parseOpenAiToolResponse(response, tools);
            Log.d(TAG, "[Http] openai-native turn hasToolCalls=" + turn.hasToolCalls()
                    + " toolCalls=" + (turn.hasToolCalls() ? turn.getToolCalls().size() : 0));
            return turn;
        }, token, deadlineAtMillis);
    }

    /**
     * Gemini 原生 Tool Calling。每轮把完整 conversation(含上轮 functionCall /
     * functionResponse)一次性发出,Gemini 自行决定本轮还要不要再调 tool。
     *
     * <p>Gemini 协议与 Anthropic / OpenAI 的关键差异:
     * <ul>
     *   <li>role 用 "user" / "model"(无 "assistant" / "tool");</li>
     *   <li>tool 调用:{@code parts:[{functionCall:{name, args}}]},不带 id 字段;</li>
     *   <li>tool 结果:{@code parts:[{functionResponse:{name, response}}]},用 name 关联
     *       (不是 id);Runtime 因此在合成 ToolCall.stepId 时按 turn 内 index 生成
     *       {@code gemini-<index>},仅作内部 ASSISTANT ↔ TOOL 消息关联用。</li>
     * </ul>
     */
    public ModelTurn callGeminiWithTools(ModelConfig config, String system,
            java.util.List<AgentMessage> conversation, java.util.List<ToolDefinition> tools)
            throws Exception {
        return callGeminiWithTools(config, system, conversation, tools, null);
    }

    /**
     * 接 {@link CancellationToken}——cancel() 时通过 abortHook 触发
     * connection.disconnect(),无需等待 read timeout(90s)。
     */
    public ModelTurn callGeminiWithTools(ModelConfig config, String system,
            java.util.List<AgentMessage> conversation, java.util.List<ToolDefinition> tools,
            com.matrix.agent.task.identity.CancellationToken token) throws Exception {
        return callGeminiWithTools(config, system, conversation, tools, token, Long.MAX_VALUE);
    }

    /**
     * cancel + deadline 感知的 Gemini 原生 Tool Calling。
     */
    public ModelTurn callGeminiWithTools(ModelConfig config, String system,
            java.util.List<AgentMessage> conversation, java.util.List<ToolDefinition> tools,
            com.matrix.agent.task.identity.CancellationToken token, long deadlineAtMillis) throws Exception {
        config.validate();
        if (config.protocol != ApiProtocol.GEMINI_GENERATE_CONTENT) {
            throw new IllegalArgumentException(
                    "Gemini Native Tool Calling 仅支持 GEMINI_GENERATE_CONTENT 协议");
        }
        Log.i(TAG, "[Http] callGeminiWithTools model=" + config.model
                + " conversationMsgs=" + conversation.size()
                + " tools=" + tools.size() + " systemChars=" + system.length()
                + " abortable=" + (token != null)
                + " deadlineMs=" + (deadlineAtMillis == Long.MAX_VALUE ? "none" : deadlineAtMillis));
        // 走 RetryPolicy 新重载——退避期间感知 cancel + 按剩余 deadline 截断 delay。
        return invokeWithRetry(() -> {
            JSONObject request = buildGeminiToolRequest(config, system, conversation, tools);
            String endpoint = config.endpoint.replace("{model}", config.model);
            JSONObject response = post(endpoint, request, "x-goog-api-key", config.apiKey,
                    null, null, token);
            ModelTurn turn = parseGeminiToolResponse(response, tools);
            Log.d(TAG, "[Http] gemini-native turn hasToolCalls=" + turn.hasToolCalls()
                    + " toolCalls=" + (turn.hasToolCalls() ? turn.getToolCalls().size() : 0));
            return turn;
        }, token, deadlineAtMillis);
    }

    /**
     * OpenAI 多轮 Tool Calling 请求体。conversation 列表序列化为 OpenAI messages 数组,
     * system 单独成一条 system message(不像 Anthropic 走 top-level system 字段)。
     */
    static JSONObject buildOpenAiToolRequest(ModelConfig config, String system,
            java.util.List<AgentMessage> conversation, java.util.List<ToolDefinition> tools)
            throws Exception {
        return OpenAiToolProtocol.buildConversationRequest(config, system, conversation, tools);
    }

    /**
     * 解析 OpenAI 多轮响应,返回 {@link ModelTurn}。
     *
     * <p>无 tool_calls 时走 {@link ModelTurn#directAnswer(String)}(finishReason=STOP);
     * 有 tool_calls 时 {@link ModelTurn#ofToolCalls(List, String)},tool_calls[].id 原样保留
     * (供下一轮 tool message 关联 tool_call_id)。
     */
    static ModelTurn parseOpenAiToolResponse(JSONObject response,
            java.util.List<ToolDefinition> tools) throws Exception {
        return OpenAiToolProtocol.parseConversationResponse(response, tools);
    }

    /**
     * Gemini 多轮 Tool Calling 请求体。
     *
     * <p>结构与 OpenAI / Anthropic 的差异:
     * <ul>
     *   <li>system 走 top-level {@code systemInstruction.parts[{text}]};</li>
     *   <li>conversation 走 {@code contents[{role, parts[*]}]},role ∈ {user, model};</li>
     *   <li>tools 走 {@code tools[{functionDeclarations:[...]}]}(双层嵌套,与 OpenAI 平铺不同);</li>
     *   <li>temperature 在 {@code generationConfig} 内(不能与 OpenAI 一样直接放顶层)。</li>
     * </ul>
     */
    static JSONObject buildGeminiToolRequest(ModelConfig config, String system,
            java.util.List<AgentMessage> conversation, java.util.List<ToolDefinition> tools)
            throws Exception {
        return GeminiToolProtocol.buildRequest(config, system, conversation, tools);
    }

    /**
     * 解析 Gemini 多轮 Tool Calling 响应。
     *
     * <p>Gemini 响应结构:
     * <ul>
     *   <li>{@code candidates[0].content.parts[i]} 可能是 {@code {text}} 或 {@code {functionCall:{name, args}}};</li>
     *   <li>{@code candidates[0].finishReason} ∈ STOP / MAX_TOKENS / SAFETY / RECITATION / OTHER
     *       (无 "TOOL_CALLS"——有 functionCall 时 finishReason 仍可能是 STOP);</li>
     *   <li>出现 functionCall 即视为 TOOL_CALLS,与 finishReason 解耦。</li>
     * </ul>
     *
     * <p>Gemini functionCall 不带 id——Runtime 按 turn 内 index 合成 {@code gemini-<index>} 作 stepId,
     * 让 Loop 内 AgentMessage.TOOL 与 ASSISTANT 关联。下一轮发请求时按 capability name 反查 model name
     * 写到 functionResponse.name,Runtime 合成 id 不进 wire。
     */
    /**
     * 把 Agent Loop conversation 序列化为 Gemini contents 数组。
     *
     * <p>规则:
     * <ul>
     *   <li>SYSTEM 跳过(已写到 systemInstruction);</li>
     *   <li>USER → {@code {role:"user", parts:[{text}]}};</li>
     *   <li>ASSISTANT → {@code {role:"model", parts:[{text?}, {functionCall:name+args}?]}};</li>
     *   <li>TOOL → 合并到下一条 USER message 的 {@code parts[{functionResponse:{name, response}}]}
     *       (Gemini 用 user role 承载 functionResponse,与 OpenAI 的 role=tool 不同)。</li>
     * </ul>
     */
    static ModelTurn parseGeminiToolResponse(JSONObject response,
            java.util.List<ToolDefinition> tools) throws Exception {
        return GeminiToolProtocol.parseResponse(response, tools);
    }

    static JSONObject buildAnthropicToolRequest(ModelConfig config, String system,
            java.util.List<AgentMessage> conversation, java.util.List<ToolDefinition> tools)
            throws Exception {
        return AnthropicToolProtocol.buildRequest(config, system, conversation, tools);
    }

    static ModelTurn parseAnthropicToolResponse(JSONObject response, java.util.List<ToolDefinition> tools)
            throws Exception {
        return AnthropicToolProtocol.parseResponse(response, tools);
    }

    private String callOpenAiCompatible(ModelConfig config, String system, String user) throws Exception {
        JSONObject body = new JSONObject()
                .put("model", config.model)
                .put("stream", false)
                .put("temperature", 0.1)
                .put("messages", new JSONArray()
                        .put(message("system", system))
                        .put(message("user", user)));
        JSONObject response = post(config.endpoint, body, "Authorization",
                config.apiKey.isEmpty() ? null : "Bearer " + config.apiKey, null, null);
        return response.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content", "");
    }

    private String callAnthropic(ModelConfig config, String system, String user) throws Exception {
        JSONObject body = new JSONObject()
                .put("model", config.model)
                .put("max_tokens", 2048)
                .put("system", system)
                .put("messages", new JSONArray().put(message("user", user)));
        JSONObject response = post(config.endpoint, body, "x-api-key", config.apiKey,
                "anthropic-version", "2023-06-01");
        return response.getJSONArray("content").getJSONObject(0).optString("text", "");
    }

    private String callGemini(ModelConfig config, String system, String user) throws Exception {
        String endpoint = config.endpoint.replace("{model}", config.model);
        JSONObject body = new JSONObject()
                .put("systemInstruction", new JSONObject()
                        .put("parts", new JSONArray().put(new JSONObject().put("text", system))))
                .put("contents", new JSONArray().put(new JSONObject()
                        .put("role", "user")
                        .put("parts", new JSONArray().put(new JSONObject().put("text", user)))))
                .put("generationConfig", new JSONObject().put("temperature", 0.1));
        JSONObject response = post(endpoint, body, "x-goog-api-key", config.apiKey, null, null);
        return response.getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                .optString("text", "");
    }

    private String callOllama(ModelConfig config, String system, String user) throws Exception {
        JSONObject body = new JSONObject()
                .put("model", config.model)
                .put("stream", false)
                .put("messages", new JSONArray()
                        .put(message("system", system))
                        .put(message("user", user)));
        JSONObject response = post(config.endpoint, body, null, null, null, null);
        return response.getJSONObject("message").optString("content", "");
    }

    private static JSONObject message(String role, String content) throws Exception {
        return new JSONObject().put("role", role).put("content", content);
    }

    private JSONObject post(String endpoint, JSONObject body,
            String header1, String value1, String header2, String value2) throws Exception {
        return post(endpoint, body, header1, value1, header2, value2, null);
    }

    /**
     * HTTP 入口接 {@link CancellationToken}。
     *
     * <p>旧实现只检查 {@code Thread.currentThread().isInterrupted()},取消只走逻辑中断,
     * HTTP 连接在 read 阻塞时无法被强制 abort——LLM 长响应取消后 socket 仍占用 read timeout(90s)。
     * 现在把 connection.disconnect 注册到 token.abortHook,token.cancel() 触发后立即断开 socket,
     * read 抛 IOException 提前出 finally。
     */
    JSONObject post(String endpoint, JSONObject body,
            String header1, String value1, String header2, String value2,
            com.matrix.agent.task.identity.CancellationToken token) throws Exception {
        return httpTransport.post(endpoint, body, header1, value1, header2, value2, token);
    }

}
