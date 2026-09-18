package com.matrix.agent.task;
import com.matrix.agent.task.redact.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Agent Loop 整体轨迹。早期为纯内存态,后续加 Room/WAL 持久化和加密。
 * Trajectory 中所有 observation 字段必须在落结构化字段前经 {@link AuditRedactor} 字段级脱敏
 * (审计侧);conversation 喂回模型时走 {@link ModelSanitizer} 保留任务语义。
 */
public final class Trajectory {
    private final long startedAtMillis;
    private final List<AgentIteration> iterations = new ArrayList<>();
    private StopReason stopReason;
    private long durationMillis;
    private int totalToolCalls;

    public Trajectory() {
        this(System.currentTimeMillis());
    }

    public Trajectory(long startedAtMillis) {
        this.startedAtMillis = startedAtMillis;
    }

    public void addIteration(AgentIteration iteration) {
        if (iteration == null) throw new IllegalArgumentException("iteration 不能为空");
        iterations.add(iteration);
    }

    public void finish(StopReason stopReason, long durationMillis, int totalToolCalls) {
        if (stopReason == null) throw new IllegalArgumentException("stopReason 不能为空");
        if (durationMillis < 0) throw new IllegalArgumentException("durationMillis 不能小于 0");
        if (totalToolCalls < 0) throw new IllegalArgumentException("totalToolCalls 不能小于 0");
        this.stopReason = stopReason;
        this.durationMillis = durationMillis;
        this.totalToolCalls = totalToolCalls;
    }

    /**
     * 受控的终态重映射——把已 finish 的 stopReason 覆盖为新值,
     * 保留 iterations / startedAtMillis / durationMillis / totalToolCalls 不变。
     *
     * <p>仅用于 Scheduler 抢占路径:Engine 在 token.cancel 后用 CANCELLED finish trajectory,
     * Scheduler.remapIfPreempted 把 outcome.finalState/stopReason 改为 PREEMPTED 时,
     * 必须同步调本方法把 trajectory.stopReason 也改为 PREEMPTED——否则 Audit 落库后会出现
     * "结构化列 PREEMPTED / trajectoryJson.trajectory.stopReason CANCELLED"的语义矛盾。
     *
     * <p><b>约束</b>:必须已经 {@link #finish} 过(否则抛 IllegalStateException),
     * 防止滥用在 finish 前覆盖初值。本方法是 mutable 的——所有持有此 Trajectory 引用的
     * 代码观察到的 stopReason 都会变为新值(抢占路径仅 Engine → Scheduler → Repository
     * 三段链路,无并发观察者,可接受)。
     *
     * @param newStopReason 新的终态原因(不能为空)
     */
    public void rewriteStopReason(StopReason newStopReason) {
        if (newStopReason == null) throw new IllegalArgumentException("newStopReason 不能为空");
        if (this.stopReason == null) {
            throw new IllegalStateException("rewriteStopReason 仅允许在 finish 之后调用");
        }
        this.stopReason = newStopReason;
    }

    public long getStartedAtMillis() { return startedAtMillis; }
    public List<AgentIteration> getIterations() { return Collections.unmodifiableList(iterations); }
    public StopReason getStopReason() { return stopReason; }
    public long getDurationMillis() { return durationMillis; }
    public int getTotalToolCalls() { return totalToolCalls; }

    /** 累计成功的 Tool Call 数。 */
    public int countSuccessfulToolCalls() {
        int count = 0;
        for (AgentIteration iter : iterations) {
            for (ToolObservation obs : iter.getObservations()) {
                if (obs.isSuccess()) count++;
            }
        }
        return count;
    }
}
