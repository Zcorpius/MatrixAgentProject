package com.matrix.agent.data.conversation;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;

/**
 * 引用回复（评估 v1.0 §4.4/§5.2）：新用户消息与被引消息的 1:1 关联 + 引用时刻的
 * 可见快照。Host 写入前校验两条消息同会话同 owner；引用不是拼 prompt——快照只作为
 * {@code ConversationContextAssembler} 的结构化候选输入。双侧 FK CASCADE：消息只经
 * 会话级 clearUserData 删除，两行同生共死。
 */
@Entity(tableName = "conversation_quote",
        primaryKeys = {"message_id"},
        indices = {
                @Index(value = {"quoted_message_id"}, name = "idx_quote_quoted"),
        },
        foreignKeys = {
                @ForeignKey(entity = ConversationMessageEntity.class,
                        parentColumns = "message_id", childColumns = "message_id",
                        onDelete = ForeignKey.CASCADE),
                @ForeignKey(entity = ConversationMessageEntity.class,
                        parentColumns = "message_id", childColumns = "quoted_message_id",
                        onDelete = ForeignKey.CASCADE),
        })
public final class ConversationQuoteEntity {

    /** 发起引用的用户消息（1:1，主键）。 */
    @androidx.annotation.NonNull
    @ColumnInfo(name = "message_id")
    public String messageId;

    /** 被引用的消息。 */
    @androidx.annotation.NonNull
    @ColumnInfo(name = "quoted_message_id")
    public String quotedMessageId;

    /** 引用时刻的可见文本快照（被引消息后续删除语义由 CASCADE 处理，快照不可变）。 */
    @androidx.annotation.NonNull
    @ColumnInfo(name = "quote_snapshot")
    public String quoteSnapshot;

    @ColumnInfo(name = "created_at_ms")
    public long createdAtMs;
}
