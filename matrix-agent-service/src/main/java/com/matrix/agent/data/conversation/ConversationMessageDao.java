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

    /** 向后翻页：sequence 严格大于 afterSequenceExclusive，升序取 limit+1 条以判定 hasAfter。 */
    @Query("SELECT * FROM conversation_message WHERE conversation_id = :conversationId"
            + " AND sequence_no > :afterSequenceExclusive"
            + " ORDER BY sequence_no ASC LIMIT :limit")
    List<ConversationMessageEntity> pageAfterAscending(String conversationId,
            long afterSequenceExclusive, int limit);

    /** 窗口首行之前是否还有更早消息（hasBefore 判定）。 */
    @Query("SELECT COUNT(*) FROM conversation_message WHERE conversation_id = :conversationId"
            + " AND sequence_no < :beforeSequenceExclusive")
    int countBefore(String conversationId, long beforeSequenceExclusive);

    /** 锚点存在性（越权/被清理/不存在统一为 false，不泄漏存在性）。 */
    @Query("SELECT COUNT(*) FROM conversation_message WHERE conversation_id = :conversationId"
            + " AND sequence_no = :anchorSequence")
    int countBySequence(String conversationId, long anchorSequence);

    /** 锚点窗口：sequence 升序取 anchor 前 beforeCount 条 + 后 afterCount 条由仓库层拼装。 */
    @Query("SELECT * FROM conversation_message WHERE conversation_id = :conversationId"
            + " AND sequence_no >= :anchorSequence"
            + " ORDER BY sequence_no ASC LIMIT :afterCount")
    List<ConversationMessageEntity> windowFromAnchorAscending(String conversationId,
            long anchorSequence, int afterCount);

    /**
     * 向前翻页：首屏用 {@code beforeSequenceExclusive < 0} 作为“从最新开始”的稳定哨兵；
     * 后续页才按 sequence 严格小于游标。不能把 -1 直接代入 {@code sequence_no < -1}，
     * 否则任何合法（正数）序号都会被过滤，造成“标题存在但页面永远没有消息”。
     */
    @Query("SELECT * FROM conversation_message WHERE conversation_id = :conversationId"
            + " AND (:beforeSequenceExclusive < 0 OR sequence_no < :beforeSequenceExclusive)"
            + " ORDER BY sequence_no DESC LIMIT :limit")
    List<ConversationMessageEntity> pageBeforeDescending(String conversationId,
            long beforeSequenceExclusive, int limit);

    @Query("UPDATE conversation_message SET status = :status, failure_code = :failureCode,"
            + " updated_at_ms = :updatedAtMs WHERE message_id = :messageId")
    int updateStatus(String messageId, int status, int failureCode, long updatedAtMs);

    /** steer 投递态推进；仅 PENDING 行生效（迟到回执不得覆盖已收敛事实）。 */
    @Query("UPDATE conversation_message SET steer_delivery_state = :deliveryState,"
            + " updated_at_ms = :updatedAtMs WHERE message_id = :messageId"
            + " AND input_kind = 1 AND steer_delivery_state = 'PENDING'")
    int updateSteerDelivery(String messageId, String deliveryState, long updatedAtMs);

    /**
     * 宿主终态镜像收敛：仍为 RUNNING 的附属 steer 行置为宿主终态。
     * 投递态 FAILED 的 steer 已自收敛为 FAILED（WHERE status = RUNNING 排除之）；
     * 投递态保留原值——“已并入”只由 OFFERED 声称，PENDING 恢复后显示“未确认并入”。
     */
    @Query("UPDATE conversation_message SET status = :status, failure_code = :failureCode,"
            + " updated_at_ms = :updatedAtMs WHERE steer_host_user_message_id = :hostMessageId"
            + " AND input_kind = 1 AND status = 1")
    int convergeSteersByHost(String hostMessageId, int status, int failureCode, long updatedAtMs);

    /**
     * 与 {@link #convergeSteersByHost} 同 WHERE 条件的 SELECT：终态事务先读出待收敛
     * 行、再逐行 {@link #updateStatus}——事务内原子等价，且调用方拿到确切行集用于
     * 补发事件（SQLite RETURNING 在 minSdk 28 的旧版本上不可用，故取先读后写）。
     */
    @Query("SELECT * FROM conversation_message WHERE steer_host_user_message_id"
            + " = :hostMessageId AND input_kind = 1 AND status = 1")
    List<ConversationMessageEntity> findRunningSteersByHost(String hostMessageId);

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
