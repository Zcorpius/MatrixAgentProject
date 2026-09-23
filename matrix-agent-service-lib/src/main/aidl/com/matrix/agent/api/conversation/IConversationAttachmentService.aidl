package com.matrix.agent.api.conversation;

import com.matrix.agent.api.conversation.ConversationAttachment;

/**
 * 受控附件 staging 的窄接口（输入交互增强 I6 §9.2，v8）：Launcher 仅打开只读
 * ParcelFileDescriptor 交给 Host——URI 授权不跨 UID 假定，任意文件路径不经 Binder
 * 传给系统服务。文本提取、大小/格式限制、净化与加密存储全部在 Host 内完成；
 * 返回的 DTO 只携带 chip 元数据，正文绝不跨 Binder。
 *
 * <p>2A 阶段仅接受 text/* 白名单格式；图片在 OnDeviceOcrPort 选型完成前
 * fail-closed（state=FAILED + UNSUPPORTED_MEDIA_FOR_TEXT_MODEL），不得以文件名、
 * EXIF 或模糊描述伪装为“图片已理解”。</p>
 */
interface IConversationAttachmentService {
    /**
     * 摄取一个附件：读取 fd（≤2MiB）、嗅探格式、提取受限文本（≤16000 字符），
     * 写入 SQLCipher；返回 READY（chip 可显示）或 FAILED（含稳定 errorCode）的
     * 投影。同一 clientOperationId 幂等重放返回既有行。owner/zone 由 Host 从
     * Binder 调用方推导。
     */
    ConversationAttachment stage(String conversationId, in ParcelFileDescriptor fd,
            String declaredMime, String displayName, String clientOperationId);

    /** 当前会话的草稿附件（未链接到消息）；按创建时间升序。 */
    List<ConversationAttachment> listDraftAttachments(String conversationId);

    /** 删除草稿附件（chip 的 ×）；已链接到消息的附件不可删（返回 INVALID_STATE）。 */
    int deleteAttachment(String attachmentId, String clientOperationId);
}
