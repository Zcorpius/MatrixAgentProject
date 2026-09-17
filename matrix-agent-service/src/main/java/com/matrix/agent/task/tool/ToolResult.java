package com.matrix.agent.task.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ToolResult {
    public enum Status {
        SUCCESS,
        POLICY_REJECTED,
        EXECUTION_FAILED,
        VERIFICATION_FAILED,
        TIMED_OUT,
        CANCELLED,
        /**
         * 写操作下发后超时 / 取消,Runtime 不能宣称"已取消"——
         * Java {@code Future.cancel(true)} 中断线程不保证撤销已发出的 AIDL / Binder /
         * Intent / 厂商 SDK 命令,Provider 可能继续设置车辆状态。返回 EXECUTION_UNKNOWN
         * 表示"命令已下发但执行结果未知",Agent Trajectory 与 UI 必须按未知态展示,
         * 后续版本接真实 Provider 后由 readback / queryCommandState 异步更新为
         * SUCCESS / VERIFICATION_FAILED / UNKNOWN。
         *
         * <p>读操作不会进入此状态——读操作被中断等于未发生,继续返回 CANCELLED / TIMED_OUT。
         */
        EXECUTION_UNKNOWN
    }

    private final Status status;
    private final String capabilityName;
    private final String message;
    private final Map<String, Object> observedState;
    private final boolean verified;
    private final long durationMillis;

    public ToolResult(
            Status status,
            String capabilityName,
            String message,
            Map<String, Object> observedState,
            boolean verified,
            long durationMillis) {
        this.status = status;
        this.capabilityName = capabilityName;
        this.message = message;
        this.observedState = Collections.unmodifiableMap(new LinkedHashMap<>(observedState));
        this.verified = verified;
        this.durationMillis = durationMillis;
    }

    public static ToolResult rejected(String capabilityName, String message) {
        return new ToolResult(
                Status.POLICY_REJECTED,
                capabilityName,
                message,
                Collections.emptyMap(),
                false,
                0L);
    }

    public Status getStatus() {
        return status;
    }

    public String getCapabilityName() {
        return capabilityName;
    }

    public String getMessage() {
        return message;
    }

    public Map<String, Object> getObservedState() {
        return observedState;
    }

    public boolean isVerified() {
        return verified;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }
}
