package com.matrix.agent.data.failure;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;

/** Private diagnostic records, deliberately separate from explicit user memories. */
@Entity(tableName = "failure_lesson", primaryKeys = {"owner", "zone", "sourceTask", "epoch"},
        indices = @Index(value = {"owner", "zone", "capability", "expiresAt"},
                name = "idx_failure_lesson_recall"))
public final class FailureLessonEntity {
    @NonNull public String owner = "";
    @NonNull public String zone = "";
    @NonNull public String sourceTask = "";
    public long epoch;
    public int version;
    @NonNull public String capability = "";
    @NonNull public String code = "";
    @NonNull public String evidenceRefs = "";
    public long createdAt;
    public long expiresAt;
}
