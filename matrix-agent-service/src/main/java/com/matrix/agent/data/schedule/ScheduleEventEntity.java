package com.matrix.agent.data.schedule;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Encrypted scheduling row. Mutations belong to the schedule store transaction boundary. */
@Entity(tableName="schedule_event", indices={@Index(name="idx_schedule_event_owner", value={"ownerUid","sequence"}, unique=false)})
public final class ScheduleEventEntity {
    @PrimaryKey(autoGenerate=true) public long sequence;
    public int ownerUid;
    @NonNull public String scheduleId = "";
    @NonNull public String runId = "";
    @NonNull public String kind = "";
    @NonNull public String safeCode = "";
    public long createdAt;
    @NonNull public String timingFactsJson = "";
}
