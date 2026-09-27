package com.matrix.agent.data.schedule;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Encrypted scheduling row. Mutations belong to the schedule store transaction boundary. */
@Entity(tableName="schedule_outbox", primaryKeys={"effectId"}, indices={@Index(name="idx_schedule_outbox_due", value={"state","nextAttemptAt"}, unique=false)})
public final class ScheduleOutboxEntity {
    @NonNull public String effectId = "";
    @NonNull public String kind = "";
    @NonNull public String scheduleId = "";
    @NonNull public String runId = "";
    public long dataEpoch;
    public long generation;
    public int state;
    public int attempt;
    public long nextAttemptAt;
    @NonNull public String bootId = "";
    public long nextElapsedAt;
    public long cutoffAt;
    @NonNull public String cursor = "";
    @NonNull public String lastError = "";
}
