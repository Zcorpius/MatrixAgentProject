package com.matrix.agent.data.audit;

import com.matrix.agent.identity.AgentRequest;

/**
 * 持久化层的终态审计写入命令。
 *
 * <p>该类型刻意只包含数据库写入所需的扁平字段。任务领域对象到本命令的映射属于
 * {@code task.persistence}；data 层不得了解 AgentOutcome、AgentRequest 或 Trajectory。
 */
public final class AuditOutcomeEntry {
    public final String requestId;
    public final String sessionId;
    public final String arbitrationKey;
    public final String actor;
    public final String zone;
    public final String userId;
    public final long startedMs;
    public final long durationMs;
    public final int iterationCount;
    public final int totalToolCalls;
    public final int successToolCalls;
    public final String stopReason;
    public final String finalState;
    /** 已由 task 侧完成脱敏并编码的完整轨迹 JSON。 */
    public final String trajectoryJson;

    public AuditOutcomeEntry(String requestId, String sessionId, String arbitrationKey,
            String actor, String zone, String userId, long startedMs, long durationMs,
            int iterationCount, int totalToolCalls, int successToolCalls, String stopReason,
            String finalState, String trajectoryJson) {
        this.requestId = requestId;
        this.sessionId = sessionId;
        this.arbitrationKey = arbitrationKey;
        this.actor = actor;
        this.zone = zone;
        this.userId = userId;
        this.startedMs = startedMs;
        this.durationMs = durationMs;
        this.iterationCount = iterationCount;
        this.totalToolCalls = totalToolCalls;
        this.successToolCalls = successToolCalls;
        this.stopReason = stopReason;
        this.finalState = finalState;
        this.trajectoryJson = trajectoryJson;
    }
}
