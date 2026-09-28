package com.matrix.agent.data.schedule;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Encrypted scheduling row. Mutations belong to the schedule store transaction boundary. */
@Entity(tableName="schedule_run", primaryKeys={"runId"}, indices={@Index(name="uq_schedule_occurrence", value={"scheduleId","occurrenceKey"}, unique=true), @Index(name="uq_schedule_runtime_request", value={"runtimeRequestId"}, unique=true), @Index(name="idx_schedule_run_owner", value={"ownerUid","scheduledAt","runId"}, unique=false), @Index(name="idx_schedule_run_state", value={"state","expiresAt"}, unique=false)})
public final class ScheduleRunEntity {
    @NonNull public String runId = "";
    @NonNull public String scheduleId = "";
    @NonNull public String occurrenceKey = "";
    public int ownerUid;
    public long dataEpoch;
    public long definitionRevision;
    public long timingRevision;
    public long dispatchGeneration;
    public boolean dispatchClaimed;
    @NonNull public String actor = "";
    @NonNull public String zone = "";
    @NonNull public String title = "";
    @NonNull public String specJson = "";
    @NonNull public String authorizationJson = "";
    public long scheduledAt;
    @Nullable public Long receivedAt;
    @Nullable public Long admittedAt;
    @Nullable public Long startedAt;
    @Nullable public Long completedAt;
    @Nullable public Long deliveredAt;
    public long expiresAt;
    public int state;
    public int deliveryStatus;
    @NonNull @androidx.room.ColumnInfo(defaultValue = "'{}'")
    public String deliveryFactsJson = "{}";
    public boolean speechClaimed;
    @NonNull public String triggerKind = "";
    @NonNull public String result = "";
    @NonNull public String reason = "";
    @NonNull public String runtimeRequestId = "";
    @NonNull public String requestHash = "";
    @NonNull public String bootId = "";
    @Nullable public Long receivedElapsed;
    @Nullable public Long startedElapsed;
    public long activeMillis;
    @Nullable public Long budgetAnchorElapsed;
    @androidx.room.ColumnInfo(defaultValue = "0")
    public int modelCalls;
    public int toolCalls;
    public long updatedAt;
}
