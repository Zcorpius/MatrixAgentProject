package com.matrix.agent.data.db;

import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import androidx.annotation.NonNull;

/**
 * Agent 单次 execute 的完整 Trajectory 持久化记录。
 *
 * <p>主键 requestId(UUID 字符串),整段 trajectory 序列化为 JSON 单行存储。
 * 索引 (sessionId, startedMs) 支持按 session 倒序查询历史(episodic memory 召回用)。
 *
 * <p>trajectoryJson 已经过 AgentEngine 内部 auditRedact 字段级脱敏
 * (climate.destination / memory.preference.value 等业务敏感字段已替换),
 * 可安全落盘;不做二次脱敏。
 */
@Entity(tableName = "trajectory",
        indices = {@Index(value = {"sessionId", "startedMs"}, name = "idx_trajectory_session_started")})
public final class TrajectoryEntity {
    @PrimaryKey
    @NonNull
    public String requestId;

    public String sessionId;

    public String arbitrationKey;

    public String actor;

    public String zone;

    public String userId;

    public long startedMs;

    public long durationMs;

    public int iterationCount;

    public int totalToolCalls;

    public int successToolCalls;

    public String stopReason;

    public String finalState;

    public String trajectoryJson;

    public long createdAtMs;
}
