package com.matrix.agent.data.schedule;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Encrypted scheduling row. Mutations belong to the schedule store transaction boundary. */
@Entity(tableName="schedule_definition", primaryKeys={"scheduleId"}, indices={@Index(name="idx_schedule_owner_state", value={"ownerUid","state"}, unique=false), @Index(name="idx_schedule_due", value={"state","nextDueAt"}, unique=false)})
public final class ScheduleDefinitionEntity {
    @NonNull public String scheduleId = "";
    public int ownerUid;
    public int ownerUserId;
    @NonNull public String ownerPackage = "";
    @NonNull public String signatureDigest = "";
    @NonNull public String actor = "";
    @NonNull public String zone = "";
    public long revision;
    public long ruleGeneration;
    public long registrationGeneration;
    @NonNull public String fixedOccurrenceKey = "";
    @NonNull public String specJson = "";
    @NonNull public String timeRuleJson = "";
    public int state;
    public int health;
    @NonNull public String reason = "";
    @Nullable public Long nextDueAt;
    @Nullable public Long nextElapsedAt;
    @NonNull public String bootId = "";
    @Nullable public Long processedAt;
    @NonNull public String processedKey = "";
    public long dataEpoch;
    public long effectiveFrom;
    public long createdAt;
    public long updatedAt;
}
