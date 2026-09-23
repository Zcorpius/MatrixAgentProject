package com.matrix.agent.task.conversation;

import com.matrix.agent.contract.AgentMessage;
import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.task.StopReason;
import com.matrix.agent.task.redact.ModelSanitizer;

/**
 * AgentOutcome → {@link AssistantReply} 的唯一投影（纯函数，JVM 可测）。
 *
 * <p>规则（设计文档 §9.2）：
 * <ul>
 *   <li>NO_TOOL_CALL 且 finalAssistantText 非空白 → MODEL_FINAL：文本过
 *       ModelSanitizer 凭据清洗 + 长度上限，超限置 truncated；</li>
 *   <li>其余一切终态（含 NO_TOOL_CALL 但文本空白、LENGTH/PROTOCOL 异常、取消、超时、
 *       预算耗尽、策略终止、执行未知）→ SYNTHESIZED_TERMINAL：固定非误导文案，
 *       不从 trajectory 反解析、不把截断片段伪装成完整回复、不退化为无信息“已完成”。</li>
 * </ul>
 * EXECUTION_UNKNOWN 的文案必须如实表达“可能已发出、结果未知”，不得暗示取消或失败。</p>
 */
public final class ConversationAssistantProjector {

    /** 对话正文单条上限：与 AgentOutcome 各终态说明对齐的展示预算（UTF-16 chars）。 */
    public static final int MAX_REPLY_CHARS = 2_000;

    private ConversationAssistantProjector() { }

    public static AssistantReply project(AgentOutcome outcome, int maxReplyChars) {
        if (outcome == null) throw new IllegalArgumentException("outcome 不能为空");
        if (maxReplyChars <= 0) throw new IllegalArgumentException("maxReplyChars 必须大于 0");

        String finalText = outcome.getFinalAssistantText();
        if (outcome.getStopReason() == StopReason.NO_TOOL_CALL
                && finalText != null && !finalText.isBlank()) {
            ModelSanitizer sanitizer = new ModelSanitizer(maxReplyChars);
            String sanitized = sanitizer.sanitize(finalText);
            boolean truncated = sanitized.length() < finalText.length();
            return AssistantReply.modelFinal(sanitized, truncated);
        }
        return AssistantReply.synthesized(synthesizedExplanation(outcome.getStopReason()));
    }

    /** 非误导终态说明：文案与 StopReason 一一对应，只描述事实，不编造成果。 */
    private static String synthesizedExplanation(StopReason reason) {
        if (reason == null) return "任务未能完成。";
        return switch (reason) {
            case NO_TOOL_CALL, DONE -> "任务未能产生有效回复。";
            case TIMEOUT -> "任务超时，未能完成。";
            case NETWORK_UNAVAILABLE -> "无法连接云端模型，请检查网络后重试。";
            case CANCELLED -> "任务已取消。";
            case PREEMPTED -> "任务被更高优先级请求打断。";
            case DEFERRED -> "任务已按你的要求推迟，稍后可继续。";
            case MAX_ITERATIONS -> "任务未在允许的步骤内完成，已停止后续操作。";
            case MAX_TOOL_CALLS -> "任务达到工具调用上限，已停止。";
            case BUDGET_EXHAUSTED -> "对话上下文已达上限，任务停止。";
            case LENGTH_EXCEEDED -> "模型输出被截断，未采纳为完整回复。";
            case PROTOCOL_ERROR -> "模型返回异常，任务未完成。";
            case POLICY_HALT -> "该请求因安全策略未执行。";
            case EXECUTION_UNKNOWN ->
                    "指令可能已发出，但执行结果未知；请查看设备当前状态确认。";
            case REJECTED -> "系统繁忙，任务未执行，请稍后重试。";
        };
    }
}
