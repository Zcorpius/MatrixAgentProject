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
        try (com.matrix.agent.debugtrace.DebugTraceContext.Scope ignored =
                com.matrix.agent.debugtrace.DebugTraceContext.bind(
                        request.getAgentRequest().getRequestId())) {
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
            return ModelTurn.directAnswer(synthesizeToolSummary(request.getConversation()));
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

    /**
     * 兼容模式单轮规划结束后的用户可读摘要。
     *
     * <p>兼容模式不做第二轮 LLM 调用——工具结果已经在 conversation 里，
     * 此处从 tool 消息（AgentMessage.Role.TOOL）的 toolName 与 content 中
     * 提取成功/失败状态与回读值，合成"已调整屏幕亮度"这类有信息量的回复。
     * 不再返回"单轮规划已执行"的内部术语（用户不应看到协议细节）。</p>
     */
    static String synthesizeToolSummary(List<AgentMessage> conversation) {
        java.util.List<String> successNames = new java.util.ArrayList<>();
        int failureCount = 0;
        String lastCapability = null;
        String lastValue = null;

        for (AgentMessage message : conversation) {
            if (message.getRole() != AgentMessage.Role.TOOL) continue;
            String content = message.getContent();
            if (content == null || content.isBlank()) continue;

            String capability = message.getToolName();
            if (content.startsWith("SUCCESS:")) {
                // knowledge.answer 的结果本身就是给用户的回答——直接用，不合成"已回答"
                if ("knowledge.answer".equals(capability)) {
                    String answer = extractAnswerText(content);
                    if (answer != null) return answer;
                }
                String friendly = com.matrix.agent.voice.CapabilitySpeechNames
                        .friendlyName(capability);
                successNames.add(friendly);
                lastCapability = capability;
                lastValue = extractPercentValue(content);
            } else if (!content.startsWith("CAPABILITY_REJECTED:")
                    && !content.startsWith("PARAMETER_REJECTED:")) {
                failureCount++;
            }
        }

        if (successNames.isEmpty() && failureCount == 0) return "任务已执行完毕。";
        if (successNames.isEmpty()) return "操作未能完成，请稍后重试。";

        // 单次成功 + 有百分比 → 自然语言："已调整屏幕亮度到 60%"
        if (successNames.size() == 1 && failureCount == 0 && lastValue != null) {
            return "已" + successNames.get(0) + "到 " + lastValue + "%";
        }
        if (successNames.size() == 1 && failureCount == 0) {
            return "已" + successNames.get(0) + "。";
        }
        if (failureCount == 0) {
            return "已完成 " + successNames.size() + " 项操作："
                    + String.join("、", successNames) + "。";
        }
        return "已完成 " + successNames.size() + " 项（"
                + String.join("、", successNames) + "），其余 "
                + failureCount + " 项未能完成。";
    }

    /** 从 knowledge.answer 的 SUCCESS 消息中提取回答文本。 */
    private static String extractAnswerText(String content) {
        // SUCCESS: 这是回答内容 observed={answerSource=...} verified=true
        int colon = content.indexOf(':');
        int observed = content.indexOf(" observed=");
        if (colon < 0 || observed < 0 || observed <= colon) return null;
        String answer = content.substring(colon + 1, observed).trim();
        if (answer.isEmpty() || answer.startsWith("这是离线问答占位结果")) return null;
        return answer;
    }

    /**
     * 从 observed={key=value,...} 中提取百分比数值（如 "60"），
     * 用于"已调整屏幕亮度到 60%"的自然语言合成。只匹配 key 含 percent 的条目。
     */
    private static String extractPercentValue(String content) {
        int start = content.indexOf("observed={");
        if (start < 0) return null;
        int end = content.indexOf('}', start);
        if (end < 0) return null;
        String body = content.substring(start + 10, end);
        // 找 percent=NN 的模式
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("[\\w.]*percent=(\\d+)").matcher(body);
        return m.find() ? m.group(1) : null;
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null) return error.getClass().getSimpleName();
        return message.length() > 120 ? message.substring(0, 120) + "…" : message;
    }

}
