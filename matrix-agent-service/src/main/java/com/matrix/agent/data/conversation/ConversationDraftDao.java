package com.matrix.agent.data.conversation;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

/**
 * 草稿两表的 DAO。条件写入规则（同 instance 更大 revision / 新 instance 替换 /
 * 拒绝 tombstone instance）由 {@code RoomConversationDraftStore} 在事务内用这些
 * 原语组装——DAO 只做无判断读写。
 */
@Dao
public interface ConversationDraftDao {

    @Query("SELECT * FROM conversation_draft WHERE owner_user_id = :ownerUserId "
            + "AND vehicle_zone = :vehicleZone AND conversation_id = :conversationId")
    ConversationDraftEntity getDraft(String ownerUserId, String vehicleZone,
            String conversationId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertDraft(ConversationDraftEntity entity);

    @Query("DELETE FROM conversation_draft WHERE owner_user_id = :ownerUserId "
            + "AND vehicle_zone = :vehicleZone AND conversation_id = :conversationId")
    void deleteDraft(String ownerUserId, String vehicleZone, String conversationId);

    @Query("DELETE FROM conversation_draft WHERE owner_user_id IN (:userIds)")
    void deleteDraftsByUsers(List<String> userIds);

    @Query("SELECT COUNT(*) FROM conversation_consumed_draft "
            + "WHERE owner_user_id = :ownerUserId AND vehicle_zone = :vehicleZone "
            + "AND conversation_id = :conversationId "
            + "AND draft_instance_id = :draftInstanceId")
    int countConsumed(String ownerUserId, String vehicleZone, String conversationId,
            String draftInstanceId);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertConsumed(ConversationConsumedDraftEntity entity);

    /** 清理谓词的输入集；按会话分组排序在 Store 内做（SQLite 版本无关）。 */
    @Query("SELECT * FROM conversation_consumed_draft")
    List<ConversationConsumedDraftEntity> listAllConsumed();

    @Query("DELETE FROM conversation_consumed_draft WHERE owner_user_id = :ownerUserId "
            + "AND vehicle_zone = :vehicleZone AND conversation_id = :conversationId "
            + "AND draft_instance_id = :draftInstanceId")
    void deleteConsumed(String ownerUserId, String vehicleZone, String conversationId,
            String draftInstanceId);

    @Query("DELETE FROM conversation_consumed_draft WHERE owner_user_id IN (:userIds)")
    void deleteConsumedByUsers(List<String> userIds);
}
