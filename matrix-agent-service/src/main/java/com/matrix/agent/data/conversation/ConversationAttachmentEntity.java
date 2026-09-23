package com.matrix.agent.data.conversation;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * 受控上下文附件（输入交互增强 I6 §9.2）：用户显式选择的资料经 Host 摄取后，
 * 以受限文本形式并入模型上下文。extracted_text 与全部元数据驻留 SQLCipher——
 * 无文件系统 blob、无孤儿二进制回收问题，天然随 clearUserData / 备份规则覆盖。
 *
 * <p>生命周期：stage 时以草稿态落行（linked_message_id = null，仅归属会话）；
 * 提交受理在同一事务内写 linked_message_id + ordinal（冻结为消息事实）；
 * 未链接的草稿附件由 7 天 GC 回收。正文只在 Host 进程内用于模型投影，绝不跨
 * Binder（DTO 只携带 chip 元数据）。</p>
 */
@Entity(tableName = "conversation_attachment",
        indices = {
                @Index(value = {"owner_user_id", "vehicle_zone"},
                        name = "idx_attachment_owner_zone"),
                @Index(value = {"conversation_id", "created_at_ms"},
                        name = "idx_attachment_conversation_time"),
                @Index(value = {"linked_message_id"},
                        name = "idx_attachment_linked_message"),
                // clientOperationId 的幂等作用域是 Host 推导的 owner/zone，而不是整库。
                // 全局唯一会让两个隔离域恰好重用 UUID 时发生 REPLACE 覆盖。
                @Index(value = {"owner_user_id", "vehicle_zone", "client_operation_id"},
                        name = "uq_attachment_owner_zone_operation", unique = true),
        })
public final class ConversationAttachmentEntity {
    /** 来源：文件选择器（2A）；粘贴文本与图片 OCR 为后续阶段预留。 */
    public static final int SOURCE_FILE = 0;
    public static final int SOURCE_PASTE = 1;
    public static final int SOURCE_IMAGE_OCR = 2;

    /** 提取状态：STAGING = Host 正在摄取；READY = 可并入模型投影；FAILED = 摄取被拒。 */
    public static final int STATE_READY = 1;
    public static final int STATE_FAILED = 2;

    @PrimaryKey @NonNull @ColumnInfo(name = "attachment_id")
    public String attachmentId;

    @NonNull @ColumnInfo(name = "owner_user_id")
    public String ownerUserId;

    @NonNull @ColumnInfo(name = "vehicle_zone")
    public String vehicleZone;

    @NonNull @ColumnInfo(name = "conversation_id")
    public String conversationId;

    @ColumnInfo(name = "source_kind")
    public int sourceKind;

    /** 嗅探后的 MIME（文本白名单或 image/*；失败行保留原声明便于诊断）。 */
    @NonNull @ColumnInfo(name = "mime_type")
    public String mimeType;

    /** chip 展示名（已截断、无路径）；原文件路径不落库。 */
    @NonNull @ColumnInfo(name = "safe_display_name")
    public String safeDisplayName;

    @ColumnInfo(name = "byte_size")
    public long byteSize;

    @ColumnInfo(name = "state")
    public int state;

    /** ConversationAttachment.ERROR_*；READY 行为 0。 */
    @ColumnInfo(name = "error_code")
    public int errorCode;

    /** 提取的受限文本（≤16k 字符）；FAILED 行为 null。 */
    @ColumnInfo(name = "extracted_text")
    public String extractedText;

    @ColumnInfo(name = "extracted_chars")
    public int extractedChars;

    /** null = 草稿附件；提交冻结后为宿主用户消息 id。 */
    @ColumnInfo(name = "linked_message_id")
    public String linkedMessageId;

    /** 提交冻结时的顺序（chip 排序回放）；草稿态为 0。 */
    @ColumnInfo(name = "ordinal")
    public int ordinal;

    @ColumnInfo(name = "created_at_ms")
    public long createdAtMs;

    /** 幂等键（clientOperationId 直达；同键重放返回既有行）。 */
    @NonNull @ColumnInfo(name = "client_operation_id")
    public String clientOperationId;
}
