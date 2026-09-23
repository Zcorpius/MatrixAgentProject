package com.matrix.agent.attachment;

import com.matrix.agent.conversation.ConversationCoordinator;
import com.matrix.agent.data.conversation.ConversationAttachmentDao;
import com.matrix.agent.data.conversation.ConversationAttachmentEntity;

import java.util.List;
import java.util.Objects;

/**
 * Coordinator 的 {@link ConversationCoordinator.AttachmentPort} 适配器（I6 §9.2）。
 *
 * <p>验证在**无事务**下完成读取判定（存在/READY/归属/数量——全部稳定字段），
 * 真正的冻结（linked_message_id）由 RoomConversationStore 在提交事务内执行；
 * 两者之间的窗口无害：并发删除会让冻结 no-op，但投影文本已物化在 AgentRequest
 * 中，模型看到的上下文与用户提交时的选择一致（受理即事实）。</p>
 */
public final class ConversationAttachmentPort
        implements ConversationCoordinator.AttachmentPort {

    private final ConversationAttachmentDao dao;
    private final AttachmentContextProjector projector;

    public ConversationAttachmentPort(ConversationAttachmentDao dao,
            AttachmentContextProjector projector) {
        this.dao = Objects.requireNonNull(dao, "dao");
        this.projector = Objects.requireNonNull(projector, "projector");
    }

    @Override
    public void validateForSubmission(String ownerUserId, String vehicleZone,
            String conversationId, List<String> attachmentIds) {
        Objects.requireNonNull(attachmentIds, "attachmentIds");
        if (attachmentIds.size() > RoomAttachmentStagingStore.MAX_PER_SUBMISSION) {
            throw new IllegalArgumentException("单次提交最多 "
                    + RoomAttachmentStagingStore.MAX_PER_SUBMISSION + " 个附件");
        }
        for (String attachmentId : attachmentIds) {
            ConversationAttachmentEntity entity = dao.getById(attachmentId);
            if (entity == null) {
                throw new IllegalArgumentException("附件不存在或已删除: " + attachmentId);
            }
            if (!entity.ownerUserId.equals(ownerUserId)
                    || !entity.vehicleZone.equals(vehicleZone)) {
                // 不泄漏存在性：越权与不存在同文案。
                throw new IllegalArgumentException("附件不存在或已删除: " + attachmentId);
            }
            if (!entity.conversationId.equals(conversationId)) {
                throw new IllegalArgumentException("附件不属于当前会话: " + attachmentId);
            }
            if (entity.linkedMessageId != null) {
                throw new IllegalArgumentException("附件已随先前消息提交: " + attachmentId);
            }
            if (entity.state != ConversationAttachmentEntity.STATE_READY) {
                throw new IllegalArgumentException("附件未就绪（摄取失败），请先移除: "
                        + attachmentId);
            }
        }
    }

    @Override
    public String projectContext(String ownerUserId, String vehicleZone,
            String conversationId, List<String> attachmentIds) {
        if (attachmentIds.isEmpty()) return "";
        List<ConversationAttachmentEntity> entities = attachmentIds.stream()
                .map(dao::getById)
                .filter(entity -> entity != null
                        && entity.state == ConversationAttachmentEntity.STATE_READY)
                .collect(java.util.stream.Collectors.toList());
        return projector.project(entities);
    }
}
