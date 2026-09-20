package com.matrix.agent.conversation;

import com.matrix.agent.task.AgentIteration;
import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.task.ToolObservation;
import com.matrix.agent.task.tool.ToolResult;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 能力事实轨迹投影器（评估 v1.0 §4.3，纯函数、JVM 可测）。
 *
 * <p>从 {@link AgentOutcome} 的 Trajectory 按 toolCallId 配对“调用快照（参数）×
 * Observation（结果/readback）”，经白名单裁剪产出 {@link CapabilityExecutionTrace}。
 * 绝不读取模型文本；参数只保留白名单键的标量值，其余一律省略（fail-closed 净化）。</p>
 */
public final class CapabilityTraceProjector {

    /** 单条轨迹的参数/读回摘要上限（UTF-16 chars）。 */
    static final int DISPLAY_MAX_CHARS = 48;

    /**
     * 参数白名单：仅这些键名的标量值可出现在 requestedDisplay/verifiedDisplay。
     * 命名同时覆盖参数名（percent/temperature/...）与 readback 键
     * （media.volume.percent/display.brightness.percent——后缀匹配）。
     */
    private static final List<String> PARAM_KEY_ALLOWLIST = List.of(
            "percent", "temperature", "level", "mode", "zone", "fan_speed", "seat",
            "media.volume.percent", "display.brightness.percent");

    private CapabilityTraceProjector() {
    }

    /** 全量投影：trajectory 无调用时返回空列表。 */
    public static List<CapabilityExecutionTrace> project(AgentOutcome outcome) {
        List<CapabilityExecutionTrace> traces = new ArrayList<>();
        if (outcome == null || outcome.getTrajectory() == null) {
            return traces;
        }
        for (AgentIteration iteration : outcome.getTrajectory().getIterations()) {
            Map<String, AgentIteration.ToolCallSnapshot> calls = new HashMap<>();
            for (AgentIteration.ToolCallSnapshot call : iteration.getToolCalls()) {
                calls.put(call.getToolCallId(), call);
            }
            for (ToolObservation observation : iteration.getObservations()) {
                AgentIteration.ToolCallSnapshot call = calls.get(observation.getToolCallId());
                traces.add(projectOne(call, observation));
            }
        }
        return traces;
    }

    private static CapabilityExecutionTrace projectOne(
            AgentIteration.ToolCallSnapshot call, ToolObservation observation) {
        String capabilityId = observation.getCapabilityName();
        ToolResult result = observation.getResult();

        String requested = call == null ? null
                : summarizeWhitelisted(call.getArguments());
        String verified = result == null ? null
                : summarizeWhitelisted(result.getObservedState());

        String outcome;
        String verification;
        if (result == null) {
            outcome = CapabilityExecutionTrace.OUTCOME_REJECTED;
            verification = CapabilityExecutionTrace.VERIFY_UNAVAILABLE;
        } else {
            switch (result.getStatus()) {
                case SUCCESS:
                    outcome = CapabilityExecutionTrace.OUTCOME_SUCCESS;
                    verification = result.isVerified()
                            ? CapabilityExecutionTrace.VERIFY_VERIFIED
                            : CapabilityExecutionTrace.VERIFY_UNAVAILABLE;
                    break;
                case VERIFICATION_FAILED:
                    outcome = CapabilityExecutionTrace.OUTCOME_FAILED;
                    verification = CapabilityExecutionTrace.VERIFY_MISMATCH;
                    break;
                case EXECUTION_UNKNOWN:
                    outcome = CapabilityExecutionTrace.OUTCOME_UNKNOWN;
                    verification = CapabilityExecutionTrace.VERIFY_UNKNOWN;
                    break;
                case POLICY_REJECTED:
                    outcome = CapabilityExecutionTrace.OUTCOME_REJECTED;
                    verification = CapabilityExecutionTrace.VERIFY_UNAVAILABLE;
                    break;
                default:
                    outcome = CapabilityExecutionTrace.OUTCOME_FAILED;
                    verification = CapabilityExecutionTrace.VERIFY_UNAVAILABLE;
                    break;
            }
        }
        return new CapabilityExecutionTrace(capabilityId,
                com.matrix.agent.voice.CapabilitySpeechNames.friendlyName(capabilityId),
                outcome, requested, verified, verification);
    }

    /** 白名单键的 “k=v” 拼接；非白名单键一律省略；整体截断到 DISPLAY_MAX_CHARS。 */
    static String summarizeWhitelisted(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String scalar = scalarOf(entry.getValue());
            if (scalar == null || !isAllowedKey(entry.getKey())) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(shortKey(entry.getKey())).append('=').append(scalar);
            if (sb.length() >= DISPLAY_MAX_CHARS) {
                break;
            }
        }
        if (sb.length() == 0) {
            return null;
        }
        return sb.length() > DISPLAY_MAX_CHARS
                ? sb.substring(0, DISPLAY_MAX_CHARS) : sb.toString();
    }

    /** 仅标量（数字/短枚举字符串）可展示；对象/数组/长文本一律省略。 */
    private static String scalarOf(Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof String) {
            String text = ((String) value).trim();
            return text.length() <= 0 || text.length() > 16 || text.contains("\n")
                    ? null : text;
        }
        return null;
    }

    private static boolean isAllowedKey(String key) {
        if (key == null) {
            return false;
        }
        String lowered = key.toLowerCase(Locale.ROOT);
        for (String allowed : PARAM_KEY_ALLOWLIST) {
            if (lowered.equals(allowed) || lowered.endsWith("." + allowed)) {
                return true;
            }
        }
        return false;
    }

    /** readback 点键（a.b.percent → percent）缩短展示。 */
    private static String shortKey(String key) {
        int dot = key.lastIndexOf('.');
        return dot >= 0 && dot < key.length() - 1 ? key.substring(dot + 1) : key;
    }
}
