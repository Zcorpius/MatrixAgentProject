package com.matrix.agent.data.schedule;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Encrypted scheduling row. Mutations belong to the schedule store transaction boundary. */
@Entity(tableName="schedule_step", primaryKeys={"runId","stepId"}, indices={@Index(name="idx_schedule_step_state", value={"runId","state"}, unique=false)})
public final class ScheduleStepEntity {
    @NonNull public String runId = "";
    @NonNull public String stepId = "";
    @NonNull public String title = "";
    @NonNull public String kind = "";
    @NonNull public String dependenciesJson = "";
    public boolean required;
    @NonNull public String inputJson = "";
    @NonNull public String result = "";
    @NonNull public String outputJson = "";
    @NonNull public String reason = "";
    public int state;
    public int attempt;
    @NonNull public String runtimeRequestId = "";
    @NonNull public String operationKey = "";
    @Nullable public Long startedAt;
    @Nullable public Long startedElapsed;
    @Nullable public Long completedAt;
    @Nullable public Long nextAttemptAt;
    public long activeMillis;
    public int toolCalls;
    public long leaseGeneration;
}
