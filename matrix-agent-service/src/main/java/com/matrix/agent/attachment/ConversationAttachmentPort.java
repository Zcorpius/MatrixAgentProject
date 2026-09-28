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
 * 提交事务再次校验归属、状态与链接；并发删除或失效会回滚整次受理，
 * 不允许模型执行一个没有对应冻结来源事实的请求。</p>
 */
public final class ConversationAttachmentPort
        implements ConversationCoordinator.AttachmentPort {

    private final ConversationAttachmentDao dao;
    private final AttachmentContextProjector projector;
    private final com.matrix.agent.data.conversation.AttachmentChunkDao chunks;
    private final com.matrix.agent.attachment.retrieval.AttachmentRetrievalProjector retrieval;

    public ConversationAttachmentPort(ConversationAttachmentDao dao,
            AttachmentContextProjector projector) {
        this(dao, null, projector, 8000);
    }
    public ConversationAttachmentPort(ConversationAttachmentDao dao,
            com.matrix.agent.data.conversation.AttachmentChunkDao chunks,
            AttachmentContextProjector projector, int maxMessageChars) {
        this.chunks = chunks;
        this.retrieval = new com.matrix.agent.attachment.retrieval.AttachmentRetrievalProjector(maxMessageChars);
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
        validateForSubmission(ownerUserId, vehicleZone, conversationId, attachmentIds);
        List<ConversationAttachmentEntity> entities = attachmentIds.stream()
                .map(dao::getById)
                .filter(entity -> entity != null
                        && entity.state == ConversationAttachmentEntity.STATE_READY)
                .collect(java.util.stream.Collectors.toList());
        return projector.project(entities);
    }
    @Override public ConversationCoordinator.AttachmentProjection retrieve(String owner, String zone,
            String conversation, List<String> ids, String question) {
        validateForSubmission(owner, zone, conversation, ids);
        List<ConversationAttachmentEntity> metadata = new java.util.ArrayList<>();
        for (String id : ids) {
            var row = dao.getById(id);
            if (row == null || !row.ownerUserId.equals(owner) || !row.vehicleZone.equals(zone)
                    || !row.conversationId.equals(conversation) || row.state != ConversationAttachmentEntity.STATE_READY
                    || row.linkedMessageId != null) throw new IllegalArgumentException("附件在检索前失效");
            metadata.add(row);
        }
        List<com.matrix.agent.attachment.retrieval.DocumentChunk> documents = new java.util.ArrayList<>();
        if (chunks != null) for (var row : chunks.load(owner, zone, conversation, ids)) {
            documents.add(new com.matrix.agent.attachment.retrieval.DocumentChunk(row.attachmentId, row.ordinal,
                    row.contentVersion, row.startChar, row.endChar, row.text));
        }
        for (var row : metadata) {
            if (row.extractedText != null && !row.extractedText.isBlank()) documents.addAll(
                    com.matrix.agent.attachment.retrieval.DocumentChunker.split(row.attachmentId, row.extractedText));
        }
        return retrieval.project(metadata, documents, question);
    }

}
