package com.matrix.agent.data.db;

import androidx.room.Entity;
import androidx.room.Index;

import androidx.annotation.NonNull;

/**
 * 四层 Memory 的 SQLCipher 持久化记录。
 *
 * <p>{@code RoomMemoryStore} 持有 preference 与 epoch 行；{@code RoomMemoryWriter}
 * 在终态写 episodic、在显式用户意图下写 semantic；{@code RoomAuditRepository} 在
 * user-data reset 时参与同一数据库事务的域数据清理。实体不是预建占位，新增 layer
 * 必须同时定义读写者、访问域和清理语义。
 *
 * <p>主键 (userId, zone, layer, key) 四元组对应 {@link com.matrix.agent.data.memory.MemoryScope#storageKey}。
 * 索引 (userId, layer) 支持按用户 + 层级的召回候选集，再由查询强制 zone 访问域。
 */
@Entity(tableName = "memory_record",
        primaryKeys = {"userId", "zone", "layer", "key"},
        indices = {@Index(value = {"userId", "layer"}, name = "idx_memory_user_layer")})
public final class MemoryRecordEntity {
    @NonNull
    public String userId;
    @NonNull
    public String zone;
    @NonNull
    public String layer;  // MemoryLayer.wireValue(): working/episodic/semantic/preference
    @NonNull
    public String key;

    public String value;
    public double score;
    public long capturedAtMs;
    public String sourceSessionId;
}
