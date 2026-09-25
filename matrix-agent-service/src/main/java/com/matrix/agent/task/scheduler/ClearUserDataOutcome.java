package com.matrix.agent.task.scheduler;
import com.matrix.agent.task.AgentRuntimeRepository;
import com.matrix.agent.data.audit.ClearOutcome;

/**
 * {@link AgentRuntimeRepository#clearUserDataDetailed()} 的返回值。
 *
 * <p>每个持久化域均明确报告 CLEARED、PENDING 或 FAILED；调用方只有在
 * {@link #isComplete()} 为真时才能提示用户全部清理完成。PENDING 对应已持久化的
 * 恢复标记，数据库重新可用时需先执行恢复清理。
 */
public final class ClearUserDataOutcome {
    public enum DomainStatus { CLEARED, PENDING, FAILED }

    private final ClearOutcome driverAudit;
    private final ClearOutcome passengerAudit;
    private final ClearOutcome globalAudit;
    private final DomainStatus memory;
    private final DomainStatus legacySource;
    private final DomainStatus conversations;
    private final DomainStatus audit;
    private final DomainStatus recoveryMarker;

    public ClearUserDataOutcome(ClearOutcome driverAudit, ClearOutcome passengerAudit) {
        this(driverAudit, passengerAudit, ClearOutcome.success(0, 0, 0));
    }

    public ClearUserDataOutcome(ClearOutcome driverAudit, ClearOutcome passengerAudit,
            ClearOutcome globalAudit) {
        this(driverAudit, passengerAudit, globalAudit, DomainStatus.CLEARED,
                DomainStatus.CLEARED, DomainStatus.CLEARED,
                auditStatus(driverAudit, passengerAudit, globalAudit), DomainStatus.CLEARED);
    }

    public ClearUserDataOutcome(ClearOutcome driverAudit, ClearOutcome passengerAudit,
            ClearOutcome globalAudit, DomainStatus memory, DomainStatus legacySource,
            DomainStatus conversations, DomainStatus audit, DomainStatus recoveryMarker) {
        this.driverAudit = driverAudit;
        this.passengerAudit = passengerAudit;
        this.globalAudit = globalAudit;
        this.memory = memory;
        this.legacySource = legacySource;
        this.conversations = conversations;
        this.audit = audit;
        this.recoveryMarker = recoveryMarker;
    }

    public ClearOutcome getDriverAudit() { return driverAudit; }
    public ClearOutcome getPassengerAudit() { return passengerAudit; }
    public ClearOutcome getGlobalAudit() { return globalAudit; }
    public DomainStatus memoryStatus() { return memory; }
    public DomainStatus legacySourceStatus() { return legacySource; }
    public DomainStatus conversationsStatus() { return conversations; }
    public DomainStatus auditStatus() { return audit; }
    public DomainStatus recoveryMarkerStatus() { return recoveryMarker; }

    public boolean isComplete() {
        return memory == DomainStatus.CLEARED && legacySource == DomainStatus.CLEARED
                && conversations == DomainStatus.CLEARED && audit == DomainStatus.CLEARED
                && recoveryMarker == DomainStatus.CLEARED;
    }

    /** 任一 zone audit 失败 → 整体 audit 维度失败,ViewModel 应提示重试。 */
    public boolean auditFailed() {
        return audit != DomainStatus.CLEARED;
    }

    public String summary() {
        return "memory=" + memory + " legacy=" + legacySource
                + " conversations=" + conversations + " audit=" + audit
                + " marker=" + recoveryMarker + " driver={" + driverAudit
                + "} passenger={" + passengerAudit + "} global={" + globalAudit + "}";
    }

    private static DomainStatus auditStatus(ClearOutcome driver, ClearOutcome passenger,
            ClearOutcome global) {
        return driver != null && !driver.isFailure()
                && passenger != null && !passenger.isFailure()
                && global != null && !global.isFailure()
                ? DomainStatus.CLEARED : DomainStatus.FAILED;
    }
}
