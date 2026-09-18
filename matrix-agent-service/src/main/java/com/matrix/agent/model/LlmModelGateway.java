package com.matrix.agent.model;

import com.matrix.agent.contract.PlannerMode;

import com.matrix.agent.contract.ModelConfig;

import com.matrix.agent.contract.ApiProtocol;

import com.matrix.agent.identity.CancellationToken;

import android.util.Log;

import com.matrix.agent.contract.AgentMessage;
import com.matrix.agent.contract.ModelGateway;
import com.matrix.agent.contract.ModelTurn;
import com.matrix.agent.contract.ModelTurnRequest;
import com.matrix.agent.contract.ToolDefinition;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.data.memory.MemoryStore;
import com.matrix.agent.session.SessionContext;

import java.util.List;

/**
 * ModelGateway 的 LLM 实现。
 *
 * 路由策略:
 * - NATIVE_TOOL_CALLING + ANTHROPIC_MESSAGES:走 {@link ModelApiClient#callAnthropicWithTools}
 *   多轮原生 Tool Calling(连续 tool_result 合并到同一 user message,Tool Call ID 透传)。
 * - NATIVE_TOOL_CALLING + OPENAI_CHAT:走 {@link ModelApiClient#callOpenAiWithTools}
 *   多轮原生 Tool Calling(每个 tool_call 一条独立 tool message,tool_call_id 透传)。
 * - NATIVE_TOOL_CALLING + GEMINI_GENERATE_CONTENT:走
 *   {@link ModelApiClient#callGeminiWithTools} 多轮原生 Tool Calling
 *   (functionResponse 合并到 user message,name 关联,Gemini 协议无 id 字段)。
 * - 其他组合(STRUCTURED_JSON_COMPATIBILITY 或不支持原生 Tool Calling 的协议):
 *   走 {@link LlmPlanner} 兼容路径,单轮 JSON 决策,看到 Observation 后直接答复。
 */
public final class LlmModelGateway implements ModelGateway {
    private static final String TAG = "MatrixAgent";
    private final ModelApiClient client;
    private final ModelConfig config;
    private final LlmPlanner compatibilityPlanner;
    private final boolean useAnthropicNative;
    private final boolean useOpenAiNative;
    private final boolean useGeminiNative;

    public LlmModelGateway(ModelApiClient client, ModelConfig config) {
        this(client, config, null);
    }

    public LlmModelGateway(ModelApiClient client, ModelConfig config, MemoryStore memoryStore) {
        this.client = client;
        this.config = config;
        this.compatibilityPlanner = new LlmPlanner(client, config, memoryStore);
        this.useAnthropicNative = config.plannerMode == PlannerMode.NATIVE_TOOL_CALLING
                && config.protocol == ApiProtocol.ANTHROPIC_MESSAGES;
        this.useOpenAiNative = config.plannerMode == PlannerMode.NATIVE_TOOL_CALLING
                && config.protocol == ApiProtocol.OPENAI_CHAT;
        this.useGeminiNative = config.plannerMode == PlannerMode.NATIVE_TOOL_CALLING
                && config.protocol == ApiProtocol.GEMINI_GENERATE_CONTENT;
        Log.i(TAG, "[LlmGateway] init provider=" + config.displayName
                + " model=" + config.model
                + " protocol=" + config.protocol
                + " plannerMode=" + config.plannerMode
                + " anthropicNativeRoute=" + useAnthropicNative
                + " openAiNativeRoute=" + useOpenAiNative
                + " geminiNativeRoute=" + useGeminiNative
                + " memoryStore=" + (memoryStore == null ? "null" : memoryStore.getClass().getSimpleName()));
    }

    /** 把 Memory 召回器透传给结构化 JSON 兼容路径。 */
    public void setMemoryRecaller(com.matrix.agent.data.memory.MemoryRecaller recaller) {
        this.compatibilityPlanner.setMemoryRecaller(recaller);
    }

    @Override
    public ModelTurn decide(ModelTurnRequest request) {
        // The task boundary provides this per-zone projection.  The model must never derive
        // tools from a mutable capability registry of its own.
        List<ToolDefinition> tools = request.getTools();
        Log.d(TAG, "[LlmGateway] decide req=" + request.getAgentRequest().getRequestId()
                + " zone=" + request.getAgentRequest().getOccupantZone()
                + " tools=" + tools.size()
                + " route=" + routeName()
                + " conversationMsgs=" + request.getConversation().size());
        try {
            com.matrix.agent.identity.CancellationToken token =
                    request.getAgentRequest().getCancellationToken();
            long deadlineAtMillis = request.getAgentRequest().getDeadlineAtMillis();
            if (useAnthropicNative) {
                ModelTurn turn = client.callAnthropicWithTools(config, request.getSystemPrompt(),
                        request.getConversation(), tools, token, deadlineAtMillis);
                Log.d(TAG, "[LlmGateway] anthropic-native returned hasToolCalls=" + turn.hasToolCalls()
                        + " toolCalls=" + (turn.hasToolCalls() ? turn.getToolCalls().size() : 0));
                return turn;
            }
            if (useOpenAiNative) {
                ModelTurn turn = client.callOpenAiWithTools(config, request.getSystemPrompt(),
                        request.getConversation(), tools, token, deadlineAtMillis);
                Log.d(TAG, "[LlmGateway] openai-native returned hasToolCalls=" + turn.hasToolCalls()
                        + " toolCalls=" + (turn.hasToolCalls() ? turn.getToolCalls().size() : 0));
                return turn;
            }
            if (useGeminiNative) {
                ModelTurn turn = client.callGeminiWithTools(config, request.getSystemPrompt(),
                        request.getConversation(), tools, token, deadlineAtMillis);
                Log.d(TAG, "[LlmGateway] gemini-native returned hasToolCalls=" + turn.hasToolCalls()
                        + " toolCalls=" + (turn.hasToolCalls() ? turn.getToolCalls().size() : 0));
                return turn;
            }
            ModelTurn compatibilityTurn = compatibilityDecide(request);
            Log.d(TAG, "[LlmGateway] compatibility returned hasToolCalls="
                    + compatibilityTurn.hasToolCalls() + " toolCalls="
                    + (compatibilityTurn.hasToolCalls() ? compatibilityTurn.getToolCalls().size() : 0));
            return compatibilityTurn;
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            Log.e(TAG, "[LlmGateway] decide failed: " + safeMessage(error), error);
            throw new IllegalStateException("LLM 模型决策失败:" + safeMessage(error), error);
        }
    }

    private String routeName() {
        if (useAnthropicNative) return "anthropic-native-tools";
        if (useOpenAiNative) return "openai-native-tools";
        if (useGeminiNative) return "gemini-native-tools";
        return "structured-json-compatibility";
    }

    private ModelTurn compatibilityDecide(ModelTurnRequest request) {
        if (hasToolResult(request.getConversation())) {
            Log.d(TAG, "[LlmGateway] compatibility: tool_result observed -> direct answer");
            return ModelTurn.directAnswer("LLM 兼容路径:单轮规划已执行,任务结束");
        }
        AgentRequest agentRequest = request.getAgentRequest();
        SessionContext context = request.getSessionContext();
        ModelTurn turn = compatibilityPlanner.decide(agentRequest, context, request.getTools());
        Log.d(TAG, "[LlmGateway] compatibility turn finish=" + turn.getFinishReason()
                + " toolCalls=" + turn.getToolCalls().size());
        return turn;
    }

    private static boolean hasToolResult(List<AgentMessage> conversation) {
        for (AgentMessage message : conversation) {
            if (message.getRole() == AgentMessage.Role.TOOL) return true;
        }
        return false;
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null) return error.getClass().getSimpleName();
        return message.length() > 120 ? message.substring(0, 120) + "…" : message;
    }

}
