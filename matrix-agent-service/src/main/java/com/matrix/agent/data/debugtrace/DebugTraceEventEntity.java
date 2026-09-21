package com.matrix.agent.data.debugtrace;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 调试构建才会写入的、已脱敏轨迹投影。
 *
 * <p>这不是审计表的镜像：它只保存供内部调试 UI 复现一个对话轮次所需的安全投影，
 * 与 {@code conversation_task_link} 的 {@code runtime_request_id} 解析结果一起固化。
 * 量产构建绝不写入此表；表仍在 SQLCipher 中，以保证 internal/debug 重新进入会话时的
 * 轨迹也受与对话正文相同的静态加密保护。</p>
 */
@Entity(tableName = "debug_trace_event", indices = {
        @Index(value = {"host_user_message_id", "timestamp_ms", "event_sequence", "part_index"},
                name = "idx_debug_trace_host_order"),
        @Index(value = {"conversation_id", "host_user_message_id"},
                name = "idx_debug_trace_conversation_host"),
        @Index(value = {"runtime_request_id"}, name = "idx_debug_trace_runtime_request"),
})
public final class DebugTraceEventEntity {

    /** traceId + partIndex：同一次长事件的每个分片都是幂等行。 */
    @PrimaryKey @androidx.annotation.NonNull @ColumnInfo(name = "event_id")
    public String eventId;

    @androidx.annotation.NonNull @ColumnInfo(name = "runtime_request_id")
    public String runtimeRequestId;

    @androidx.annotation.NonNull @ColumnInfo(name = "conversation_task_id")
    public String conversationTaskId;

    @androidx.annotation.NonNull @ColumnInfo(name = "conversation_id")
    public String conversationId;

    /** 调试面板锚点：轨迹永远挂在发起该轮的用户消息上。 */
    @androidx.annotation.NonNull @ColumnInfo(name = "host_user_message_id")
    public String hostUserMessageId;

    /** 同一次 emit 的各分片共享序号，读取时再按 partIndex 重组。 */
    @ColumnInfo(name = "event_sequence")
    public long eventSequence;

    @ColumnInfo(name = "timestamp_ms")
    public long timestampMs;

    @androidx.annotation.NonNull @ColumnInfo(name = "phase")
    public String phase;

    @androidx.annotation.NonNull @ColumnInfo(name = "trace_id")
    public String traceId;

    @ColumnInfo(name = "part_index")
    public int partIndex;

    @ColumnInfo(name = "part_count")
    public int partCount;

    /** 已过 DebugTraceRedactor 的内容；绝不写入原始请求、认证或审计 payload。 */
    @androidx.annotation.NonNull @ColumnInfo(name = "payload")
    public String payload;

    @ColumnInfo(name = "schema_version")
    public int schemaVersion;
}
