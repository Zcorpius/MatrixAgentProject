package com.matrix.agent.data.db;

import androidx.room.Entity;

import androidx.annotation.NonNull;

/**
 * Session 维度历史记录——给 episodic memory 召回提供数据源。
 *
 * <p>{@code RoomMemoryWriter} 仅在可持久化的任务终态写入脱敏的
 * {@code EpisodicSummary}；{@code EpisodicMemorySourceImpl} 按 user/zone 召回。
 * 它不保存原始用户文本或完整轨迹；v2 可保存经过白名单验证的少量能力回读事实。
 */
@Entity(tableName = "session_history",
        primaryKeys = {"userId", "zone", "sessionId", "startedAtMillis"})
public final class SessionHistoryEntity {
    @NonNull
    public String userId;
    @NonNull
    public String zone;
    @NonNull
    public String sessionId;
    public long startedAtMillis;

    public String actor;
    public String finalState;
    public String stopReason;
    public long durationMs;
    public int turnCount;
    public String trajectoryJson;
}
