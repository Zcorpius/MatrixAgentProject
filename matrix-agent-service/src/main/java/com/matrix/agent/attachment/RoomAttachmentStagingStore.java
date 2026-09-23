package com.matrix.agent.attachment;

import android.util.Log;

import com.matrix.agent.api.conversation.ConversationAttachment;
import com.matrix.agent.data.conversation.ConversationAttachmentDao;
import com.matrix.agent.data.conversation.ConversationAttachmentEntity;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 附件 staging 的 Room 投影（I6 §9.2）。写入前经 {@link AttachmentTextExtractor}
 * 三道防线；幂等（clientOperationId 重放返回既有行）；正文与元数据全驻 SQLCipher。
 *
 * <p>线程模型：先用短事务创建 {@code STAGING} 行，随后在 Host IO executor 读取 fd；
 * 因此 Binder 线程从不等待文件解析。linkToMessage / clearForUsers / GC 由调用方在
 * 各自事务内驱动。提取失败收敛为 FAILED 行（chip 可见原因），不向 Binder 抛异常。</p>
 */
public final class RoomAttachmentStagingStore {

    private static final String TAG = "MatrixAgent";
    /** 草稿附件保留期：超期未提交即回收（chip 遗忘与存储上界）。 */
    public static final long DRAFT_RETENTION_MS = 7L * 24 * 60 * 60 * 1000;
    /** 展示名上限（截断到合法代码点边界）。 */
    private static final int MAX_DISPLAY_NAME_CHARS = 64;
    /** 单次提交附件数上限。 */
    public static final int MAX_PER_SUBMISSION = 4;

    public interface TransactionRunner {
        void runInTransaction(Runnable body);
    }

    private final ConversationAttachmentDao dao;
    private final TransactionRunner transaction;

    public RoomAttachmentStagingStore(ConversationAttachmentDao dao,
            TransactionRunner transaction) {
        this.dao = Objects.requireNonNull(dao, "dao");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
    }

    /** beginStage 的结果；created=false 表示同一 scope 的幂等重放，调用方不得再读取 fd。 */
    public record StageStart(ConversationAttachmentEntity attachment, boolean created) { }

    /**
     * 在短事务内建立 STAGING 行。PFD 的阻塞读取必须在此方法返回之后、Host IO executor
     * 中完成；重放直接复用已有行，绝不启动第二个读取任务。
     */
    public StageStart beginStage(String ownerUserId, String vehicleZone, String conversationId,
            String declaredMime, String displayName, String clientOperationId) {
        final ConversationAttachmentEntity[] result = new ConversationAttachmentEntity[1];
        final boolean[] created = {false};
        transaction.runInTransaction(() -> {
            ConversationAttachmentEntity replay = dao.getByOperation(ownerUserId, vehicleZone,
                    clientOperationId);
            if (replay != null) {
                result[0] = replay;
                return;
            }
            ConversationAttachmentEntity entity = newStagingRow(ownerUserId, vehicleZone,
                    conversationId, declaredMime, displayName, clientOperationId);
            dao.upsert(entity);
            result[0] = entity;
            created[0] = true;
        });
        return new StageStart(result[0], created[0]);
    }

    /**
     * IO 线程完成提取后收敛 STAGING 行。用户在读取期间删除 chip 时行可能已消失；此时
     * 安全 no-op，绝不把已删除附件重新写回。
     */
    public void completeStage(String attachmentId, InputStream input, String declaredMime) {
        ConversationAttachmentEntity entity = dao.getById(attachmentId);
        if (entity == null || entity.state != ConversationAttachment.STATE_STAGING
                || entity.linkedMessageId != null) {
            return;
        }
        try {
            AttachmentTextExtractor.Extraction extraction =
                    AttachmentTextExtractor.extract(input, declaredMime);
            entity.mimeType = extraction.sniffedMime();
            entity.state = ConversationAttachmentEntity.STATE_READY;
            entity.errorCode = 0;
            entity.extractedText = extraction.text();
            entity.extractedChars = extraction.text().codePointCount(0, extraction.text().length());
            entity.byteSize = extraction.sourceByteSize();
        } catch (AttachmentTextExtractor.RejectedException rejected) {
            // 不记录文件名；文件名本身也可能是敏感上下文。
            Log.i(TAG, "[Attachment] 摄取拒绝 type=" + rejected.errorCode);
            markFailed(entity, rejected.errorCode);
        } catch (IOException transport) {
            Log.w(TAG, "[Attachment] 读取失败 type="
                    + transport.getClass().getSimpleName());
            // 大小越界由 extractor 的 RejectedException 精确标记；这里是实际 IO 失败。
            markFailed(entity, com.matrix.agent.api.common.MatrixErrorCode.TASK_FAILED);
        }
        transaction.runInTransaction(() -> {
            ConversationAttachmentEntity current = dao.getById(attachmentId);
            if (current != null && current.state == ConversationAttachment.STATE_STAGING
                    && current.linkedMessageId == null) {
                dao.upsert(entity);
            }
        });
    }

    /** IO executor 拒绝或 fd 在打开前失效时的确定失败收敛。 */
    public void failStage(String attachmentId, int errorCode) {
        transaction.runInTransaction(() -> {
            ConversationAttachmentEntity current = dao.getById(attachmentId);
            if (current == null || current.state != ConversationAttachment.STATE_STAGING
                    || current.linkedMessageId != null) return;
            markFailed(current, errorCode);
            dao.upsert(current);
        });
    }

    /** Legacy synchronous helper retained for narrow JVM tests. Production must use begin/complete. */
    public ConversationAttachmentEntity stage(String ownerUserId, String vehicleZone,
            String conversationId, InputStream input, String declaredMime,
            String displayName, String clientOperationId) {
        StageStart started = beginStage(ownerUserId, vehicleZone, conversationId, declaredMime,
                displayName, clientOperationId);
        if (!started.created()) return started.attachment();
        completeStage(started.attachment().attachmentId, input, declaredMime);
        ConversationAttachmentEntity completed = dao.getById(started.attachment().attachmentId);
        return completed == null ? started.attachment() : completed;
    }

    private static ConversationAttachmentEntity newStagingRow(String ownerUserId,
            String vehicleZone, String conversationId, String declaredMime, String displayName,
            String clientOperationId) {
        ConversationAttachmentEntity entity = new ConversationAttachmentEntity();
        entity.attachmentId = UUID.randomUUID().toString();
        entity.ownerUserId = ownerUserId;
        entity.vehicleZone = vehicleZone;
        entity.conversationId = conversationId;
        entity.sourceKind = ConversationAttachmentEntity.SOURCE_FILE;
        entity.mimeType = declaredMime == null || declaredMime.isBlank()
                ? "application/octet-stream" : declaredMime;
        entity.safeDisplayName = safeName(displayName);
        entity.byteSize = 0L;
        entity.state = ConversationAttachment.STATE_STAGING;
        entity.errorCode = 0;
        entity.extractedText = null;
        entity.extractedChars = 0;
        entity.linkedMessageId = null;
        entity.ordinal = 0;
        entity.createdAtMs = System.currentTimeMillis();
        entity.clientOperationId = Objects.requireNonNull(clientOperationId,
                "clientOperationId");
        return entity;
    }

    private static void markFailed(ConversationAttachmentEntity entity, int errorCode) {
        entity.state = ConversationAttachmentEntity.STATE_FAILED;
        entity.errorCode = errorCode;
        entity.extractedText = null;
        entity.extractedChars = 0;
        entity.byteSize = 0;
    }

    public List<ConversationAttachmentEntity> listDraft(String conversationId) {
        return dao.listDraft(conversationId);
    }

    public List<ConversationAttachmentEntity> listByMessage(String messageId) {
        return dao.listByMessage(messageId);
    }

    /** chip 删除：仅草稿可删；已冻结返回 false。 */
    public boolean deleteDraft(String ownerUserId, String vehicleZone, String attachmentId) {
        return dao.deleteDraft(ownerUserId, vehicleZone, attachmentId) > 0;
    }

    public void clearForUsers(List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) return;
        transaction.runInTransaction(() -> dao.deleteByUsers(userIds));
    }

    /** GC：回收超期草稿附件（best-effort；失败只影响存储上界）。 */
    public int cleanupExpiredDrafts(long nowMs) {
        List<ConversationAttachmentEntity> expired = dao.listExpiredDrafts(
                nowMs - DRAFT_RETENTION_MS);
        if (expired.isEmpty()) return 0;
        transaction.runInTransaction(() -> {
            for (ConversationAttachmentEntity entity : expired) {
                dao.deleteById(entity.attachmentId);
            }
        });
        Log.i(TAG, "[Attachment] 草稿附件 GC removed=" + expired.size());
        return expired.size();
    }

    /** DTO 投影（chip 元数据；正文不跨 Binder）。 */
    public static ConversationAttachment toDto(ConversationAttachmentEntity entity) {
        return new ConversationAttachment(entity.attachmentId, entity.conversationId,
                entity.mimeType, entity.safeDisplayName, entity.byteSize, entity.state,
                entity.errorCode, entity.extractedChars, entity.createdAtMs);
    }

    public static List<ConversationAttachment> toDtoList(
            List<ConversationAttachmentEntity> entities) {
        List<ConversationAttachment> result = new ArrayList<>(entities.size());
        for (ConversationAttachmentEntity entity : entities) {
            result.add(toDto(entity));
        }
        return result;
    }

    private static String safeName(String displayName) {
        String name = displayName == null ? "附件" : displayName.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0 && slash < name.length() - 1) name = name.substring(slash + 1);
        if (name.isBlank()) name = "附件";
        if (name.length() > MAX_DISPLAY_NAME_CHARS) {
            int end = MAX_DISPLAY_NAME_CHARS;
            if (Character.isHighSurrogate(name.charAt(end - 1))) end--;
            name = name.substring(0, end) + "…";
        }
        return name;
    }
}
