package com.matrix.agent.data.schedule;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Encrypted scheduling row. Mutations belong to the schedule store transaction boundary. */
@Entity(tableName="schedule_control", primaryKeys={"ownerUid","operationId"})
public final class ScheduleControlEntity {
    public int ownerUid;
    @NonNull public String operationId = "";
    @NonNull public String requestHash = "";
    public int code;
    @NonNull public String scheduleId = "";
    @NonNull public String runId = "";
    public long revision;
    public long sequence;
    @NonNull public String message = "";
    public long createdAt;
}
