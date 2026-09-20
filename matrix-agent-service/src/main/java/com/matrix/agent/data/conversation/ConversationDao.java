package com.matrix.agent.data.conversation;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface ConversationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(ConversationEntity entity);

    @Query("SELECT * FROM conversation WHERE conversation_id = :conversationId")
    ConversationEntity getById(String conversationId);

    @Query("SELECT * FROM conversation WHERE owner_user_id = :ownerUserId"
            + " AND (:includeArchived = 1 OR archived_at_ms IS NULL)"
            + " ORDER BY updated_at_ms DESC LIMIT :limit")
    List<ConversationEntity> listByOwner(String ownerUserId, boolean includeArchived, int limit);

    @Query("UPDATE conversation SET updated_at_ms = :updatedAtMs"
            + " WHERE conversation_id = :conversationId")
    void touchUpdated(String conversationId, long updatedAtMs);

    @Query("UPDATE conversation SET archived_at_ms = :archivedAtMs"
            + " WHERE conversation_id = :conversationId")
    void archive(String conversationId, long archivedAtMs);

    /** clearUserData 覆盖范围：按 owner 删除线程（消息/关联随 repository 同事务级联删）。 */
    @Query("DELETE FROM conversation WHERE owner_user_id IN (:userIds)")
    int deleteByOwners(List<String> userIds);

    @Query("SELECT COUNT(*) FROM conversation")
    int countAll();
}
