package com.matrix.agent.data.schedule;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Encrypted scheduling row. Mutations belong to the schedule store transaction boundary. */
@Entity(tableName="schedule_arm", primaryKeys={"ownerUserId"})
public final class ScheduleArmEntity {
    public int ownerUserId;
    public long dataEpoch;
    public long armGeneration;
    public long appliedGeneration;
    @Nullable public Long desiredDueAt;
    @Nullable public Long elapsedDueAt;
    @NonNull public String bootId = "";
    public int state;
    @NonNull public String lastError = "";
}
