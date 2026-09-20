package com.matrix.agent.data.conversation;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface ConversationMessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(ConversationMessageEntity entity);

    @Query("SELECT * FROM conversation_message WHERE message_id = :messageId")
    ConversationMessageEntity getById(String messageId);

    @Query("SELECT * FROM conversation_message WHERE idempotency_key = :key LIMIT 1")
    ConversationMessageEntity getByIdempotencyKey(String key);

    /** sequence 在插入事务内经本查询分配（事务写锁串行化并发提交）。 */
    @Query("SELECT MAX(sequence_no) FROM conversation_message WHERE conversation_id = :conversationId")
    Long maxSequence(String conversationId);

    /** 最新 limit 条，sequence 降序（新在前）；repository 侧反转为升序。 */
    @Query("SELECT * FROM conversation_message WHERE conversation_id = :conversationId"
            + " ORDER BY sequence_no DESC LIMIT :limit")
    List<ConversationMessageEntity> latestDescending(String conversationId, int limit);

    /** 向前翻页：sequence 严格小于 beforeSequenceExclusive，降序取 limit+1 条以判定 hasMore。 */
    @Query("SELECT * FROM conversation_message WHERE conversation_id = :conversationId"
            + " AND sequence_no < :beforeSequenceExclusive"
            + " ORDER BY sequence_no DESC LIMIT :limit")
    List<ConversationMessageEntity> pageBeforeDescending(String conversationId,
            long beforeSequenceExclusive, int limit);

    @Query("UPDATE conversation_message SET status = :status, failure_code = :failureCode,"
            + " updated_at_ms = :updatedAtMs WHERE message_id = :messageId")
    int updateStatus(String messageId, int status, int failureCode, long updatedAtMs);

    /** COMPLETED 的 user/assistant 文本（种子装配输入）；升序返回。 */
    @Query("SELECT * FROM conversation_message WHERE conversation_id = :conversationId"
            + " AND status = :completedStatus AND role IN (:userRole, :assistantRole)"
            + " ORDER BY sequence_no DESC LIMIT :limit")
    List<ConversationMessageEntity> latestCompletedDescending(String conversationId,
            int completedStatus, int userRole, int assistantRole, int limit);

    @Query("DELETE FROM conversation_message WHERE conversation_id IN"
            + " (SELECT conversation_id FROM conversation WHERE owner_user_id IN (:userIds))")
    int deleteByOwnerUsers(List<String> userIds);

    @Query("SELECT COUNT(*) FROM conversation_message")
    int countAll();
}
