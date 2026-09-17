package com.matrix.agent.task;

import com.matrix.agent.task.tool.ToolCall;
import com.matrix.agent.task.tool.ToolResult;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条 Tool Observation:关联一个 ToolCall 和它的执行结果或 Policy 拒绝原因,
 * 用于回传给模型做下一轮决策。Observation 必须包装成结构化文本,不抛异常出循环。
 */
public final class ToolObservation {
    private final String toolCallId;
    private final String capabilityName;
    private final ToolResult result;            // null 表示 Policy 拒绝,未到达 Provider
    private final String rejectionReason;       // 非 null 表示 Policy 拒绝
    private final boolean capabilityBlocked;    // 能力级拒绝(不可上诉)

    private ToolObservation(String toolCallId, String capabilityName, ToolResult result,
            String rejectionReason, boolean capabilityBlocked) {
        this.toolCallId = toolCallId;
        this.capabilityName = capabilityName;
        this.result = result;
        this.rejectionReason = rejectionReason;
        this.capabilityBlocked = capabilityBlocked;
    }

    public static ToolObservation of(ToolCall call, ToolResult result) {
        if (call == null || result == null) throw new IllegalArgumentException("call/result 不能为空");
        return new ToolObservation(call.getStepId(), call.getCapabilityName(), result, null, false);
    }

    public static ToolObservation rejected(ToolCall call, String reason, boolean capabilityBlocked) {
        if (call == null) throw new IllegalArgumentException("call 不能为空");
        if (reason == null || reason.isEmpty()) throw new IllegalArgumentException("拒绝原因不能为空");
        return new ToolObservation(call.getStepId(), call.getCapabilityName(), null, reason, capabilityBlocked);
    }

    /**
     * 用已有字段重新拼一份 Observation,供 {@link ModelSanitizer} / {@link AuditRedactor}
     * 之类需要做内容变换的路径使用。
     * 传 result 则视为执行结果(覆盖 rejection);否则按 rejectionReason/capabilityBlocked 渲染。
     */
    public static ToolObservation fromParts(String toolCallId, String capabilityName,
            ToolResult result, String rejectionReason, boolean capabilityBlocked) {
        if (toolCallId == null || toolCallId.isEmpty()) {
            throw new IllegalArgumentException("toolCallId 不能为空");
        }
        if (capabilityName == null || capabilityName.isEmpty()) {
            throw new IllegalArgumentException("capabilityName 不能为空");
        }
        return new ToolObservation(toolCallId, capabilityName, result, rejectionReason, capabilityBlocked);
    }

    public String getToolCallId() { return toolCallId; }
    public String getCapabilityName() { return capabilityName; }
    public ToolResult getResult() { return result; }
    public String getRejectionReason() { return rejectionReason; }
    public boolean isCapabilityBlocked() { return capabilityBlocked; }

    public boolean isSuccess() {
        return result != null && result.isSuccess();
    }

    /** 转成 LLM 可读的 Observation 文本。 */
    public String toToolContent() {
        if (rejectionReason != null) {
            String prefix = capabilityBlocked ? "CAPABILITY_REJECTED" : "PARAMETER_REJECTED";
            return prefix + ": " + rejectionReason
                    + (capabilityBlocked ? "(该能力已被禁用,不能再尝试)" : "(请修正参数后重试)");
        }
        if (result == null) return "ERROR: 无 Observation";
        Map<String, Object> observed = result.getObservedState() == null
                ? Collections.emptyMap() : new LinkedHashMap<>(result.getObservedState());
        return result.getStatus() + ": " + result.getMessage()
                + " observed=" + observed
                + " verified=" + result.isVerified();
    }

    public AgentMessage toToolMessage() {
        return AgentMessage.tool(toolCallId, capabilityName, toToolContent());
    }
}
