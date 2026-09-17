package com.matrix.agent.data.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;

/** Replayable, bounded event log. The (taskId, sequence) key makes ordering authoritative. */
@Entity(tableName = "agent_task_event", primaryKeys = {"taskId", "sequence"},
        indices = {@Index(value = {"taskId", "sequence"}, name = "idx_agent_task_event_replay")})
public final class AgentTaskEventEntity {
    @NonNull public String taskId;
    public long sequence;
    public long elapsedRealtimeMs;
    public int type;
    public int state;
    @NonNull public String safePayload = "";
}
