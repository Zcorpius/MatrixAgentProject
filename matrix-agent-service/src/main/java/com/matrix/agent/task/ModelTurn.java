package com.matrix.agent.task;

import com.matrix.agent.task.tool.ToolCall;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 单轮模型决策的输出。assistantMessage 已含 toolCalls,观察 finishReason 判断是否继续 Loop。
 *
 * <p>严格化:工厂方法不再静默降级——
 * <ul>
 *   <li>{@link #of(String, FinishReason)} 收到 TOOL_CALLS 必须抛异常(必须用 {@link #ofToolCalls});</li>
 *   <li>{@link #of(String, FinishReason)} 收到 STOP + 空 content 必须抛异常(空答复不是 STOP);</li>
 *   <li>NONE 允许空 content——Provider 协议不一致时 caller 应显式传 NONE。</li>
 * </ul>
 * 这样 AgentEngine 可以信任 FinishReason 不被工厂方法掩盖,据此走正确的终止路径。
 */
public final class ModelTurn {
    private final AgentMessage assistantMessage;
    private final FinishReason finishReason;
    private final int tokenEstimateChars;

    public ModelTurn(AgentMessage assistantMessage, FinishReason finishReason, int tokenEstimateChars) {
        if (assistantMessage == null) throw new IllegalArgumentException("assistantMessage 不能为空");
        if (finishReason == null) throw new IllegalArgumentException("finishReason 不能为空");
        this.assistantMessage = assistantMessage;
        this.finishReason = finishReason;
        this.tokenEstimateChars = tokenEstimateChars < 0 ? 0 : tokenEstimateChars;
    }

    /** 模型选择直接答复(STOP),本轮不调 tool。content 必须非空。 */
    public static ModelTurn directAnswer(String content) {
        if (content == null || content.trim().isEmpty()) {
            throw new IllegalArgumentException("directAnswer 必须带非空 content");
        }
        return new ModelTurn(AgentMessage.assistant(content, Collections.emptyList()),
                FinishReason.STOP, content.length());
    }

    /** 模型请求调用 tool(TOOL_CALLS)。content 可空。 */
    public static ModelTurn ofToolCalls(List<ToolCall> calls, String content) {
        if (calls == null || calls.isEmpty()) {
            throw new IllegalArgumentException("ofToolCalls 至少需要 1 个 ToolCall");
        }
        List<ToolCall> copy = new ArrayList<>(calls);
        int chars = (content == null ? 0 : content.length());
        for (ToolCall call : copy) {
            chars += call.getCapabilityName().length();
            if (call.getArguments() != null) chars += call.getArguments().toString().length();
        }
        return new ModelTurn(AgentMessage.assistant(content, copy), FinishReason.TOOL_CALLS, chars);
    }

    /**
     * 显式指定 FinishReason 的工厂方法——只接受无 tool_call 的状态:
     * STOP / LENGTH / NONE。TOOL_CALLS 必须用 {@link #ofToolCalls}。
     *
     * <p>Provider Adapter 解析 finish_reason / stop_reason 后必须用此方法,
     * 而不是简单 {@link #directAnswer(String)}——后者硬编码 STOP,会把 LENGTH 截断错误归为正常完成。
     *
     * <p>严格化规则:
     * <ul>
     *   <li>TOOL_CALLS → 抛 {@link IllegalArgumentException}(旧实现静默降级为 STOP,掩盖协议错误);</li>
     *   <li>STOP + 空 content → 抛 {@link IllegalArgumentException}
     *       (旧实现接受空 content,模型"啥也没说就完成"被错判为正常完成);</li>
     *   <li>LENGTH → 允许任意 content(可能因截断为空);</li>
     *   <li>NONE → 允许任意 content(Provider 协议不一致的兜底,AgentEngine 据此走 PROTOCOL_ERROR)。</li>
     * </ul>
     */
    public static ModelTurn of(String content, FinishReason finishReason) {
        if (finishReason == null) throw new IllegalArgumentException("finishReason 不能为空");
        if (finishReason == FinishReason.TOOL_CALLS) {
            throw new IllegalArgumentException(
                    "TOOL_CALLS 必须用 ofToolCalls 创建,of() 用于无 tool_call 的 STOP/LENGTH/NONE");
        }
        if (finishReason == FinishReason.STOP && (content == null || content.trim().isEmpty())) {
            throw new IllegalArgumentException(
                    "STOP 必须带非空 content——空答复不是有效完成,caller 应映射为 NONE");
        }
        return new ModelTurn(AgentMessage.assistant(content, Collections.emptyList()),
                finishReason, content == null ? 0 : content.length());
    }

    public AgentMessage getAssistantMessage() { return assistantMessage; }
    public List<ToolCall> getToolCalls() { return assistantMessage.getToolCalls(); }
    public FinishReason getFinishReason() { return finishReason; }
    public int getTokenEstimateChars() { return tokenEstimateChars; }

    public boolean hasToolCalls() { return !assistantMessage.getToolCalls().isEmpty(); }
}
