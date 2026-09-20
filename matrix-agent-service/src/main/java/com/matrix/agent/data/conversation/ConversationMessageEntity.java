package com.matrix.agent.data.conversation;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 对话消息（设计文档 §5.2）。text 只存定稿：partial 转写走内存事件，绝不落库。
 * (conversation_id, sequence_no) 复合唯一 + idempotency_key 全局唯一在实体注解显式声明。
 */
@Entity(tableName = "conversation_message",
        indices = {
                @Index(value = {"conversation_id", "sequence_no"},
                        name = "uq_conversation_message_seq", unique = true),
                @Index(value = {"conversation_task_id"},
                        name = "idx_conversation_message_task"),
                @Index(value = {"idempotency_key"},
                        name = "uq_conversation_message_idem", unique = true),
        })
public final class ConversationMessageEntity {
    @PrimaryKey @androidx.annotation.NonNull @ColumnInfo(name = "message_id")
    public String messageId;

    @ColumnInfo(name = "conversation_id")
    @androidx.annotation.NonNull
    public String conversationId;

    @ColumnInfo(name = "sequence_no")
    public long sequenceNo;

    /** ConversationMessage.ROLE_*。 */
    @ColumnInfo(name = "role")
    public int role;

    /** ConversationMessage.STATUS_*（PersistedMessageStatus 的冻结投影）。 */
    @ColumnInfo(name = "status")
    public int status;

    /** ConversationMessage.CHANNEL_*；仅 USER 消息非 NONE，可空以兼容历史行。 */
    @ColumnInfo(name = "channel")
    public Integer channel;

    @ColumnInfo(name = "text")
    @androidx.annotation.NonNull
    public String text;

    @ColumnInfo(name = "language_tag")
    public String languageTag;

    /** 触发本条消息的对话任务；ASSISTANT/SYSTEM 行为创建该任务的 id，独立行为 null。 */
    @ColumnInfo(name = "conversation_task_id")
    public String conversationTaskId;

    @ColumnInfo(name = "reply_to_message_id")
    public String replyToMessageId;

    /** SUCCESS(0) 之外的稳定错误码；无错误为 0。 */
    @ColumnInfo(name = "failure_code")
    public int failureCode;

    @ColumnInfo(name = "created_at_ms")
    public long createdAtMs;

    @ColumnInfo(name = "updated_at_ms")
    public long updatedAtMs;

    /** TEXT=clientOperationId 派生 / PTT=voiceSessionId+ordinal / WAKE=wakeEventId+ordinal。 */
    @ColumnInfo(name = "idempotency_key")
    public String idempotencyKey;

    /** ConversationMessage.INPUT_*；v8 回填 INPUT_PRIMARY。 */
    @ColumnInfo(name = "input_kind")
    public int inputKind;

    /** 宿主主用户消息 id；仅 INPUT_STEER 行非 null。 */
    @ColumnInfo(name = "steer_host_user_message_id")
    public String steerHostUserMessageId;

    /** 投递态（'PENDING'/'OFFERED'/'FAILED'）；仅 INPUT_STEER 行非 null。 */
    @ColumnInfo(name = "steer_delivery_state")
    public String steerDeliveryState;

    @ColumnInfo(name = "schema_version")
    public int schemaVersion;
}
