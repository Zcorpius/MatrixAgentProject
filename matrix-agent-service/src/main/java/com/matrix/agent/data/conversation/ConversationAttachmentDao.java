package com.matrix.agent.data.conversation;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

/**
 * 附件表 DAO。条件写入（幂等重放 / 提交冻结 / GC 谓词）由 staging store 在事务内
 * 用这些原语组装——DAO 只做无判断读写，与草稿 DAO 的分工纪律一致。
 */
@Dao
public interface ConversationAttachmentDao {

    @Query("SELECT * FROM conversation_attachment WHERE attachment_id = :attachmentId")
    ConversationAttachmentEntity getById(String attachmentId);

    /** 幂等键直达（stage 重放）。 */
    @Query("SELECT * FROM conversation_attachment WHERE client_operation_id"
            + " = :clientOperationId AND owner_user_id = :ownerUserId"
            + " AND vehicle_zone = :vehicleZone")
    ConversationAttachmentEntity getByOperation(String ownerUserId, String vehicleZone,
            String clientOperationId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(ConversationAttachmentEntity entity);

    /** 草稿附件（未链接），按提交顺序回放。 */
    @Query("SELECT * FROM conversation_attachment WHERE conversation_id = :conversationId"
            + " AND linked_message_id IS NULL ORDER BY created_at_ms ASC, attachment_id ASC")
    List<ConversationAttachmentEntity> listDraft(String conversationId);

    /** 已冻结到消息的附件，按提交顺序（DTO contextAttachments 投影）。 */
    @Query("SELECT * FROM conversation_attachment WHERE linked_message_id = :messageId"
            + " ORDER BY ordinal ASC")
    List<ConversationAttachmentEntity> listByMessage(String messageId);

    /** 提交冻结：草稿行绑定为消息事实。 */
    @Query("UPDATE conversation_attachment SET linked_message_id = :messageId,"
            + " ordinal = :ordinal WHERE attachment_id = :attachmentId"
            + " AND linked_message_id IS NULL")
    int linkToMessage(String attachmentId, String messageId, int ordinal);

    @Query("DELETE FROM conversation_attachment WHERE attachment_id = :attachmentId"
            + " AND owner_user_id = :ownerUserId AND vehicle_zone = :vehicleZone"
            + " AND linked_message_id IS NULL")
    int deleteDraft(String ownerUserId, String vehicleZone, String attachmentId);

    @Query("DELETE FROM conversation_attachment WHERE owner_user_id IN (:userIds)")
    void deleteByUsers(List<String> userIds);

    /** GC 谓词输入集：超过保留期的草稿附件（清理在 store 内做最终判定）。 */
    @Query("SELECT * FROM conversation_attachment WHERE linked_message_id IS NULL"
            + " AND created_at_ms < :expireBeforeMs")
    List<ConversationAttachmentEntity> listExpiredDrafts(long expireBeforeMs);

    @Query("DELETE FROM conversation_attachment WHERE attachment_id = :attachmentId")
    void deleteById(String attachmentId);
}
