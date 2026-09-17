package com.matrix.agent.task.policy;

/**
 * Policy 决策。
 *
 * 引入拒绝二分:
 * - CAPABILITY 能力级拒绝(不可上诉):R3、capability 未注册、区域越权、副驾写主驾区。
 *   模型不能通过换参数绕过,Agent Loop 会把对应 capability 加入禁用集合。
 * - PARAMETER 参数级拒绝(可修正重试):数值越界、格式错、缺可补全参数。
 *   模型可在剩余预算内换参数重试,权限不放宽。
 */
public final class PolicyDecision {
    public enum RejectionType { CAPABILITY, PARAMETER }

    private final boolean allowed;
    private final String reason;
    private final RejectionType rejectionType;

    private PolicyDecision(boolean allowed, String reason, RejectionType type) {
        this.allowed = allowed;
        this.reason = reason;
        this.rejectionType = type;
    }

    public static PolicyDecision allow() {
        return new PolicyDecision(true, "ALLOW", null);
    }

    /** 能力级拒绝(不可上诉)。 */
    public static PolicyDecision denyCapability(String reason) {
        return new PolicyDecision(false, reason, RejectionType.CAPABILITY);
    }

    /** 参数级拒绝(模型可在剩余预算内修正重试)。 */
    public static PolicyDecision denyParameter(String reason) {
        return new PolicyDecision(false, reason, RejectionType.PARAMETER);
    }

    public boolean isAllowed() { return allowed; }
    public String getReason() { return reason; }
    public RejectionType getRejectionType() { return rejectionType; }
}
