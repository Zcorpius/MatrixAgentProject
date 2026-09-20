package com.matrix.agent.data.conversation;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

/** 附属标记（收藏/备注）：upsert 幂等；owner 索引服务"我的收藏"类查询。 */
@Dao
public interface ConversationMessageAnnotationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(ConversationMessageAnnotationEntity entity);

    @Query("SELECT * FROM conversation_message_annotation WHERE message_id = :messageId"
            + " AND owner_user_id = :ownerUserId LIMIT 1")
    ConversationMessageAnnotationEntity get(String messageId, String ownerUserId);

    @Query("DELETE FROM conversation_message_annotation WHERE message_id = :messageId"
            + " AND owner_user_id = :ownerUserId")
    int delete(String messageId, String ownerUserId);

    @Query("SELECT COUNT(*) FROM conversation_message_annotation")
    int countAll();
}
