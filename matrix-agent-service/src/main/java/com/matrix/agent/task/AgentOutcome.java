package com.matrix.agent.task;
import com.matrix.agent.task.redact.*;

import com.matrix.agent.task.tool.ToolResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Agent Loop 单次 execute 的对外结果。
 *
 * <p>数据分两层,避免 Audit Trajectory 成为真实 ToolResult 的唯一数据源。
 * <ul>
 *   <li>{@link #getTrajectory()} —— Audit 视图,字段级脱敏。UI/Log/未来持久化用。</li>
 *   <li>{@link #getInternalResults()} —— Runtime 内部可信域,保留真实 ToolResult。
 *       Orchestrator/Task API/厂商业务层需要结构化结果时用这个。</li>
 *   <li>{@link #getResults()} —— 派生自 Audit Trajectory(脱敏后)。<b>已过时</b>,
 *       保留兼容,新调用方应改用 {@link #getInternalResults()}。</li>
 * </ul>
 */
public final class AgentOutcome {
    private final String requestId;
    private final TaskState finalState;
    private final StopReason stopReason;
    private final Trajectory trajectory;
    private final List<ToolResult> internalResults;
    private final long durationMillis;

    public AgentOutcome(String requestId, TaskState finalState, StopReason stopReason,
            Trajectory trajectory, long durationMillis) {
        this(requestId, finalState, stopReason, trajectory, durationMillis,
                Collections.emptyList());
    }

    public AgentOutcome(String requestId, TaskState finalState, StopReason stopReason,
            Trajectory trajectory, long durationMillis, List<ToolResult> internalResults) {
        if (requestId == null) throw new IllegalArgumentException("requestId 不能为空");
        if (finalState == null) throw new IllegalArgumentException("finalState 不能为空");
        if (stopReason == null) throw new IllegalArgumentException("stopReason 不能为空");
        if (trajectory == null) throw new IllegalArgumentException("trajectory 不能为空");
        this.requestId = requestId;
        this.finalState = finalState;
        this.stopReason = stopReason;
        this.trajectory = trajectory;
        this.durationMillis = durationMillis;
        this.internalResults = internalResults == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(internalResults));
    }

    public String getRequestId() { return requestId; }
    public TaskState getFinalState() { return finalState; }
    public StopReason getStopReason() { return stopReason; }
    public Trajectory getTrajectory() { return trajectory; }
    public long getDurationMillis() { return durationMillis; }

    /**
     * Runtime 内部可信域——保留真实 ToolResult,不含 audit 脱敏占位符。
     * 业务调用方需要结构化结果(memory preference 真实值、navigation destination 等)时用这个。
     * <b>不能</b>直接渲染到 UI/Log,需要再过 AuditRedactor。
     */
    public List<ToolResult> getInternalResults() {
        return internalResults;
    }

    /**
     * 派生视图:按迭代顺序展开 Audit Trajectory 中的 Observation 为 ToolResult 列表。
     * Policy 拒绝会被翻译成 {@link ToolResult.Status#POLICY_REJECTED}。
     *
     * @deprecated 返回的是 audit 视图(脱敏后),memory.preference.* 的值会变成 {@code <memory>}。
     *     业务调用方需要真实值请用 {@link #getInternalResults()}。保留是为了让旧 UI/测试逐步迁移。
     */
    @Deprecated
    public List<ToolResult> getResults() {
        List<ToolResult> results = new ArrayList<>();
        for (AgentIteration iteration : trajectory.getIterations()) {
            for (ToolObservation observation : iteration.getObservations()) {
                results.add(toToolResult(observation));
            }
        }
        return Collections.unmodifiableList(results);
    }

    private static ToolResult toToolResult(ToolObservation observation) {
        if (observation.getResult() != null) return observation.getResult();
        return ToolResult.rejected(observation.getCapabilityName(), observation.getRejectionReason());
    }
}
