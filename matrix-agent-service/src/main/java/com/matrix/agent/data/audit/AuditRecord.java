package com.matrix.agent.data.audit;

/**
 * Audit 只读视图——给 episodic memory / UI 回放使用。
 *
 * <p>字段对应 TrajectoryEntity;加 incremental events 后扩展。
 */
public final class AuditRecord {
    private final String requestId;
    private final String sessionId;
    private final String actor;
    private final String zone;
    private final String userId;
    private final long startedMs;
    private final long durationMs;
    private final String stopReason;
    private final String finalState;
    private final int iterationCount;
    private final int totalToolCalls;
    private final int successToolCalls;
    private final String trajectoryJson;

    public AuditRecord(String requestId, String sessionId, String actor, String zone,
            String userId, long startedMs, long durationMs, String stopReason, String finalState,
            int iterationCount, int totalToolCalls, int successToolCalls, String trajectoryJson) {
        this.requestId = requestId;
        this.sessionId = sessionId;
        this.actor = actor;
        this.zone = zone;
        this.userId = userId;
        this.startedMs = startedMs;
        this.durationMs = durationMs;
        this.stopReason = stopReason;
        this.finalState = finalState;
        this.iterationCount = iterationCount;
        this.totalToolCalls = totalToolCalls;
        this.successToolCalls = successToolCalls;
        this.trajectoryJson = trajectoryJson;
    }

    public String getRequestId() { return requestId; }
    public String getSessionId() { return sessionId; }
    public String getActor() { return actor; }
    public String getZone() { return zone; }
    public String getUserId() { return userId; }
    public long getStartedMs() { return startedMs; }
    public long getDurationMs() { return durationMs; }
    public String getStopReason() { return stopReason; }
    public String getFinalState() { return finalState; }
    public int getIterationCount() { return iterationCount; }
    public int getTotalToolCalls() { return totalToolCalls; }
    public int getSuccessToolCalls() { return successToolCalls; }
    public String getTrajectoryJson() { return trajectoryJson; }
}
