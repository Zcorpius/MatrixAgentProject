package com.matrix.agent.data.conversation;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;

/**
 * 已消费草稿的 tombstone（I4 §7.2 草稿复活防线的 Host 侧半边）。
 *
 * <p>提交受理事务删除草稿行时写入；saveDraft 拒绝已 tombstone 的 instance。
 * 清理谓词：同一会话最多保留 128 条，且最长保留 7 天。Launcher 的 keyed lane 已经
 * 保证同会话 save/submit 的全序；tombstone 用于覆盖这个在途窗口与短暂 Binder 重试，
 * 不承担永久历史存储职责。</p>
 */
@Entity(tableName = "conversation_consumed_draft",
        primaryKeys = {"owner_user_id", "vehicle_zone", "conversation_id",
                "draft_instance_id"})
public final class ConversationConsumedDraftEntity {
    @NonNull @ColumnInfo(name = "owner_user_id")
    public String ownerUserId;

    @NonNull @ColumnInfo(name = "vehicle_zone")
    public String vehicleZone;

    @NonNull @ColumnInfo(name = "conversation_id")
    public String conversationId;

    @NonNull @ColumnInfo(name = "draft_instance_id")
    public String draftInstanceId;

    @ColumnInfo(name = "consumed_at_ms")
    public long consumedAtMs;
}
