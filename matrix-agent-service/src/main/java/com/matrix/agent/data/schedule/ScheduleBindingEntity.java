package com.matrix.agent.data.schedule;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Encrypted scheduling row. Mutations belong to the schedule store transaction boundary. */
@Entity(tableName="schedule_binding", primaryKeys={"bindingId"}, indices={@Index(name="idx_schedule_binding_owner", value={"ownerUid","eventId"}, unique=false)})
public final class ScheduleBindingEntity {
    @NonNull public String bindingId = "";
    public int ownerUid;
    public int ownerUserId;
    public long dataEpoch;
    @NonNull public String authority = "";
    public long calendarId;
    public long eventId;
    @NonNull public String originalInstanceKey = "";
    @NonNull public String sourceRevision = "";
    @NonNull public String reminderOwner = "";
    public long lastVerifiedAt;
    @NonNull public String state = "";
}
