package com.matrix.agent.data.schedule;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Encrypted scheduling row. Mutations belong to the schedule store transaction boundary. */
@Entity(tableName="schedule_acceptance", primaryKeys={"runtimeRequestId"}, indices={@Index(name="idx_schedule_acceptance_run", value={"runId"}, unique=false)})
public final class ScheduleAcceptanceEntity {
    @NonNull public String runtimeRequestId = "";
    @NonNull public String runId = "";
    @NonNull public String stepId = "";
    @NonNull public String requestHash = "";
    public int ownerUid;
    public long dataEpoch;
    public long acceptedSequence;
    public int state;
    @NonNull public String executionHandle = "";
    @NonNull public String specJson = "";
    @NonNull public String actor = "";
    @NonNull public String zone = "";
    @NonNull public String result = "";
    @NonNull public String reason = "";
    public long acceptedAt;
    @Nullable public Long startedAt;
    @Nullable public Long completedAt;
}
