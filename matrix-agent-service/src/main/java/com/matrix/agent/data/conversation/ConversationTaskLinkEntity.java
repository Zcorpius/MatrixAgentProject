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

    /**
     * 写时净化的能力事实轨迹（CapabilityExecutionTrace 列表 JSON）。
     * null = 未投影（v8 起列存在，阶段 2 起有写入方）；读侧 null 规约为空列表。
     * 不存原始审计 payload、模型文本、凭据或可逆敏感字段。
     */
    @ColumnInfo(name = "execution_trace_json")
    public String executionTraceJson;

    /** 轨迹投影版本（脱敏器版本）；null = 无轨迹。 */
    @ColumnInfo(name = "trace_projection_version")
    public Integer traceProjectionVersion;

    // ---- ModelExecutionSnapshot（输入交互增强 I5 §8.2，v12 追加列）----
    // 提交受理时写入的受限模型快照：此后当前模型无论如何切换，本任务“用的是哪个
    // 配置代际”不可变。全部为非秘密字段（无 API key / 完整 endpoint）；null = 旧数据
    // 或快照供应方未装配（读侧规约为“未知”，不倒填）。

    /** provider ID（如 glm / anthropic / on_device）。 */
    @ColumnInfo(name = "model_provider_id")
    public String modelProviderId;

    /** 模型 ID（端侧为模型目录名）。 */
    @ColumnInfo(name = "model_id")
    public String modelId;

    /** ModelRuntimeStatus.BACKEND_* 投影（云端 / 端侧 / 无）。 */
    @ColumnInfo(name = "model_backend")
    public Integer modelBackend;

    /** SecureModelConfigStore 的单调配置代际。 */
    @ColumnInfo(name = "config_generation")
    public Integer configGeneration;

    /** 非秘密字段的 SHA-256 指纹（provider|model|protocol|端点类别|generation）。 */
    @ColumnInfo(name = "config_fingerprint")
    public String configFingerprint;
}
