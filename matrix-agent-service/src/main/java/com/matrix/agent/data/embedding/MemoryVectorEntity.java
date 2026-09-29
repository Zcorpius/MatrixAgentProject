package com.matrix.agent.data.embedding;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import com.matrix.agent.data.db.MemoryRecordEntity;

@Entity(tableName = "memory_vector", primaryKeys = {"userId", "zone", "layer", "key"},
        foreignKeys = @ForeignKey(entity = MemoryRecordEntity.class,
                parentColumns = {"userId", "zone", "layer", "key"},
                childColumns = {"userId", "zone", "layer", "key"}, onDelete = ForeignKey.CASCADE))
public final class MemoryVectorEntity {
    @NonNull public String userId;
    @NonNull public String zone;
    @NonNull public String layer;
    @NonNull public String key;
    public int indexVersion;
    @NonNull public String modelVersion;
    public int dimension;
    @NonNull public String digest;
    public long epoch;
    @NonNull public byte[] vector;
}
