package com.matrix.agent.task;

import com.matrix.agent.data.audit.ClearOutcome;

/**
 * {@link AgentRuntimeRepository#clearUserDataDetailed()} 的返回值。
 *
 * <p>组合 driver / passenger 两个 zone 的 {@link ClearOutcome},让 ViewModel 据此选择
 * "已清空" vs "上下文已清,审计删除失败,请稍后重试"文案——避免主流程(MemoryStore /
 * SteerMailbox / Session)已成功但 Audit 部分失败时,UI 仍显示完整成功的语义错位。
 *
 * <p>主流程失败(memoryStore.clearUserDataAndBump 抛 IllegalStateException 等)由
 * {@code clearUserDataDetailed} 直接抛 RuntimeException,ViewModel 走"清空失败 [...]"
 * 路径——不进 ClearUserDataOutcome。
 */
public final class ClearUserDataOutcome {
    private final ClearOutcome driverAudit;
    private final ClearOutcome passengerAudit;

    public ClearUserDataOutcome(ClearOutcome driverAudit, ClearOutcome passengerAudit) {
        this.driverAudit = driverAudit;
        this.passengerAudit = passengerAudit;
    }

    public ClearOutcome getDriverAudit() { return driverAudit; }
    public ClearOutcome getPassengerAudit() { return passengerAudit; }

    /** 任一 zone audit 失败 → 整体 audit 维度失败,ViewModel 应提示重试。 */
    public boolean auditFailed() {
        return driverAudit != null && driverAudit.isFailure()
                || passengerAudit != null && passengerAudit.isFailure();
    }

    public String summary() {
        return "driver={" + driverAudit + "} passenger={" + passengerAudit + "}";
    }
}
