package com.matrix.agent.data.debugtrace;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 调试轨迹持久化记录（评估 v1.0 §4.3 契约 2/5 / §5.2）：仅
 * `MATRIX_DEBUG_TRACE_UI=true` 的 internal/debug Host 写入同一 SQLCipher 数据库。
 *
 * <p>是可恢复的调试历史，不是正式对话消息、审计原文或模型上下文——普通导出绝不包含。
 * 随宿主用户消息级联删除（FK CASCADE）；`debugTraceUi=false` 时 Host 启动清除全表。
 * 按 task 设置有界事件数，超限由 Emitter 写入显式 TRUNCATED 事件。</p>
 */
@Entity(tableName = "debug_trace_event_record",
        indices = {
                @Index(value = {"host_user_message_id", "conversation_task_id",
                        "event_sequence"}, name = "idx_debug_trace_host_task_seq"),
        },
        foreignKeys = @ForeignKey(
                entity = com.matrix.agent.data.conversation.ConversationMessageEntity.class,
                parentColumns = "message_id",
                childColumns = "host_user_message_id",
                onDelete = ForeignKey.CASCADE))
public final class DebugTraceEventRecordEntity {

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "row_id")
    public long rowId;

    /** Emitter 分片重组键。 */
    @ColumnInfo(name = "trace_id")
    @androidx.annotation.NonNull
    public String traceId;

    /** 宿主用户消息（FK CASCADE：消息删除→轨迹消失）。 */
    @ColumnInfo(name = "host_user_message_id")
    @androidx.annotation.NonNull
    public String hostUserMessageId;

    @ColumnInfo(name = "conversation_task_id")
    @androidx.annotation.NonNull
    public String conversationTaskId;

    /** 防迟到实时事件进入新一轮面板。 */
    @ColumnInfo(name = "generation")
    public long generation;

    /** 单调递增顺序号（同 task 内 Emitter 指定；历史与实时共用同一序）。 */
    @ColumnInfo(name = "event_sequence")
    public long eventSequence;

    /** ROUND_START / MODEL_REASONING / MODEL_PROPOSED / POLICY_DECIDED / REQUEST_DELIVERED / DEVICE_VERIFIED / ROUND_END / TRUNCATED。 */
    @ColumnInfo(name = "phase")
    @androidx.annotation.NonNull
    public String phase;

    /** 已净化的事件正文（分片载荷）。 */
    @ColumnInfo(name = "payload")
    @androidx.annotation.NonNull
    public String payload;

    @ColumnInfo(name = "part_index")
    public int partIndex;

    @ColumnInfo(name = "part_count")
    public int partCount;

    @ColumnInfo(name = "timestamp_ms")
    public long timestampMs;
}
