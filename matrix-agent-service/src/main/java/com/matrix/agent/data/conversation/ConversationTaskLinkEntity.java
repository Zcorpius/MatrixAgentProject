package com.matrix.agent.data.conversation;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 对话任务关联（设计文档 §5.2）：conversationTaskId 是对话控制与恢复主键；
 * runtimeRequestId 只负责关联既有 trajectory/audit，二者绝不混用。
 * read_only_hint 为提交期分类快照——恢复对账按其分流 FAILED / EXECUTION_UNKNOWN。
 */
@Entity(tableName = "conversation_task_link",
        indices = {
                @Index(value = {"runtime_request_id"},
                        name = "uq_conversation_link_request", unique = true),
                @Index(value = {"conversation_id"},
                        name = "idx_conversation_link_conversation"),
        })
public final class ConversationTaskLinkEntity {
    @PrimaryKey @androidx.annotation.NonNull @ColumnInfo(name = "conversation_task_id")
    public String conversationTaskId;

    @ColumnInfo(name = "runtime_request_id")
    @androidx.annotation.NonNull
    public String runtimeRequestId;

    @ColumnInfo(name = "conversation_id")
    @androidx.annotation.NonNull
    public String conversationId;

    @ColumnInfo(name = "user_message_id")
    @androidx.annotation.NonNull
    public String userMessageId;

    @ColumnInfo(name = "assistant_message_id")
    public String assistantMessageId;

    @ColumnInfo(name = "read_only_hint")
    public boolean readOnlyHint;

    /** PersistedMessageStatus 冻结值；null = 未终态（恢复对账的扫描目标）。 */
    @ColumnInfo(name = "terminal_status")
    public Integer terminalStatus;

    @ColumnInfo(name = "created_at_ms")
    public long createdAtMs;

    /** keyed lane 出队时刻；null = 尚未派发。 */
    @ColumnInfo(name = "started_at_ms")
    public Long startedAtMs;

    @ColumnInfo(name = "terminal_at_ms")
    public Long terminalAtMs;
}
