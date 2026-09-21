package com.matrix.agent.conversation;

import com.matrix.agent.task.conversation.AssistantReply;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 执行事实 → 对话终答的可信兜底。
 *
 * <p>模型终答通常优先保留；但“任务完成”这类占位句不含任何可验证业务语义。此时仅使用
 * {@link CapabilityExecutionTrace} 的白名单投影改写为 Host facts，保证最终回复与状态卡的
 * requested/readback 一致。这里绝不读取原始工具参数、原始模型文本或审计 trajectory。</p>
 */
public final class ConversationExecutionReplyProjector {

    private ConversationExecutionReplyProjector() {
    }

    /** 有事实且模型仅给出泛化完成语时，返回事实终答；否则保留原答复。 */
    public static AssistantReply replaceGenericCompletion(AssistantReply reply,
            List<CapabilityExecutionTrace> traces) {
        if (reply == null) throw new IllegalArgumentException("reply 不能为空");
        if (!isGenericCompletion(reply.text()) || traces == null || traces.isEmpty()) {
            return reply;
        }
        List<String> sentences = new ArrayList<>();
        for (CapabilityExecutionTrace trace : traces) {
            String sentence = sentenceFor(trace);
            if (sentence != null && !sentence.isBlank()) {
                sentences.add(sentence);
            }
        }
        return sentences.isEmpty() ? reply
                : AssistantReply.executionFacts(String.join("\n", sentences));
    }

    private static boolean isGenericCompletion(String text) {
        String normalized = text == null ? "" : text.strip()
                .replaceAll("[。！？!？，,\\s]", "").toLowerCase(Locale.ROOT);
        return normalized.equals("任务完成") || normalized.equals("已完成")
                || normalized.equals("操作完成") || normalized.equals("完成");
    }

    private static String sentenceFor(CapabilityExecutionTrace trace) {
        if (trace == null) return null;
        String name = trace.friendlyName;
        if (CapabilityExecutionTrace.OUTCOME_UNKNOWN.equals(trace.outcome)
                || CapabilityExecutionTrace.VERIFY_UNKNOWN.equals(trace.verificationState)) {
            return "已尝试" + name + "，但执行结果未知，请检查设备当前状态。";
        }
        if (CapabilityExecutionTrace.OUTCOME_REJECTED.equals(trace.outcome)) {
            return name + "未执行：被安全策略拒绝。";
        }
        if (CapabilityExecutionTrace.OUTCOME_FAILED.equals(trace.outcome)) {
            return name + "未完成。";
        }
        if (!CapabilityExecutionTrace.OUTCOME_SUCCESS.equals(trace.outcome)) return null;

        String requested = displayValue(trace.requestedDisplay);
        String verified = displayValue(trace.verifiedDisplay);
        if ("system.media.set_volume".equals(trace.capabilityId)) {
            return settingSentence("媒体音量", requested, verified, trace.verificationState);
        }
        if ("system.display.set_brightness".equals(trace.capabilityId)) {
            return settingSentence("屏幕亮度", requested, verified, trace.verificationState);
        }
        if (CapabilityExecutionTrace.VERIFY_MISMATCH.equals(trace.verificationState)) {
            return "已执行" + name + "，但核验结果与请求不一致：请求"
                    + fallback(requested, "值") + "，设备当前为" + fallback(verified, "未知") + "。";
        }
        if (CapabilityExecutionTrace.VERIFY_VERIFIED.equals(trace.verificationState)) {
            return "已完成：" + name + stateSuffix(requested, verified) + "。";
        }
        return "已完成：" + name + "，但当前无法核验结果。";
    }

    private static String settingSentence(String subject, String requested, String verified,
            String verification) {
        if (CapabilityExecutionTrace.VERIFY_MISMATCH.equals(verification)) {
            return "已将" + subject + "设置为" + fallback(requested, "请求值")
                    + "，设备当前为" + fallback(verified, "未知") + "。";
        }
        if (CapabilityExecutionTrace.VERIFY_VERIFIED.equals(verification)) {
            return "已将" + subject + "设置为" + fallback(verified, fallback(requested, "目标值")) + "。";
        }
        return "已请求调整" + subject + "，但当前无法核验结果。";
    }

    private static String stateSuffix(String requested, String verified) {
        if (verified != null) return "，设备当前为" + verified;
        if (requested != null) return "，请求值为" + requested;
        return "";
    }

    private static String displayValue(String raw) {
        if (raw == null || raw.isBlank()) return null;
        // 这些格式来自 CapabilityTraceProjector 的白名单显示字段，而非未净化参数。
        if (raw.matches("percent=-?\\d+(?:\\.\\d+)?")) {
            return raw.substring("percent=".length()) + "%";
        }
        return raw;
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
