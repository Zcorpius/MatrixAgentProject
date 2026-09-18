package com.matrix.agent.data.memory;

/**
 * Episodic 记忆的单条写入命令。
 *
 * <p>终态过滤、摘要构建和任务领域对象投影由 task 侧适配器完成；data 层只负责在同一
 * 事务内校验 epoch、写入实体并在成功后失效缓存。
 */
public final class EpisodicWrite {
    public final String requestId;
    public final String userId;
    public final String zone;
    public final String sessionId;
    public final String actor;
    public final long startedAtMillis;
    public final String finalState;
    public final String stopReason;
    public final long durationMs;
    public final int turnCount;
    /** 安全摘要 JSON；不包含原始用户文本、工具参数或工具结果。 */
    public final String summaryJson;
    public final long requestEpoch;

    public EpisodicWrite(String requestId, String userId, String zone, String sessionId,
            String actor, long startedAtMillis, String finalState, String stopReason,
            long durationMs, int turnCount, String summaryJson, long requestEpoch) {
        this.requestId = requestId;
        this.userId = userId;
        this.zone = zone;
        this.sessionId = sessionId;
        this.actor = actor;
        this.startedAtMillis = startedAtMillis;
        this.finalState = finalState;
        this.stopReason = stopReason;
        this.durationMs = durationMs;
        this.turnCount = turnCount;
        this.summaryJson = summaryJson;
        this.requestEpoch = requestEpoch;
    }
}
