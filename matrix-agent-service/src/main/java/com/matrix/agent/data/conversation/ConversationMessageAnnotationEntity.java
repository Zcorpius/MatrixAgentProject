package com.matrix.agent.data.conversation;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;

/**
 * 消息附属标记（评估 v1.0 §4.4/§5.2 收藏与个人备注）。
 *
 * <p>只允许用户标记/备注/取消标记：不改 {@code conversation_message} 本体、不自动
 * 进入模型上下文、不影响任务策略。message 删除（clearUserData 会话级联）时
 * FK CASCADE 一并消失——不留跨 epoch 孤儿标记。复合主键 (message_id, owner_user_id)
 * 保证同一 owner 对同一消息至多一条。</p>
 */
@Entity(tableName = "conversation_message_annotation",
        primaryKeys = {"message_id", "owner_user_id"},
        indices = @Index(value = {"owner_user_id"}, name = "idx_annotation_owner"),
        foreignKeys = @ForeignKey(
                entity = ConversationMessageEntity.class,
                parentColumns = "message_id",
                childColumns = "message_id",
                onDelete = ForeignKey.CASCADE))
public final class ConversationMessageAnnotationEntity {

    @androidx.annotation.NonNull
    @ColumnInfo(name = "message_id")
    public String messageId;

    @androidx.annotation.NonNull
    @ColumnInfo(name = "owner_user_id")
    public String ownerUserId;

    /** 收藏标记；false = 未收藏（保留行以便 userNote 共存）。 */
    @ColumnInfo(name = "favorite")
    public boolean favorite;

    /** 可空：用户个人备注；默认不进入模型上下文。 */
    @ColumnInfo(name = "user_note")
    public String userNote;

    @ColumnInfo(name = "created_at_ms")
    public long createdAtMs;

    @ColumnInfo(name = "updated_at_ms")
    public long updatedAtMs;
}
