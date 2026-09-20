package com.matrix.agent.data.conversation;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** 用户可见对话线程（设计文档 §5.2）。owner/zone 由 Host 推导，客户端不可指定。 */
@Entity(tableName = "conversation",
        indices = @Index(value = {"owner_user_id", "vehicle_zone"},
                name = "idx_conversation_owner_zone"))
public final class ConversationEntity {
    @PrimaryKey @androidx.annotation.NonNull @ColumnInfo(name = "conversation_id")
    public String conversationId;

    @ColumnInfo(name = "owner_user_id")
    @androidx.annotation.NonNull
    public String ownerUserId;

    @ColumnInfo(name = "vehicle_zone")
    @androidx.annotation.NonNull
    public String vehicleZone;

    /** 首条消息后异步生成；可空。 */
    @ColumnInfo(name = "title")
    public String title;

    @ColumnInfo(name = "created_at_ms")
    public long createdAtMs;

    @ColumnInfo(name = "updated_at_ms")
    public long updatedAtMs;

    /** 归档时间；null = 未归档。归档线程不被唤醒路由复活。 */
    @ColumnInfo(name = "archived_at_ms")
    public Long archivedAtMs;

    @ColumnInfo(name = "schema_version")
    public int schemaVersion;
}
