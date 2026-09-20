package com.matrix.agent.data.conversation;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

/** 分支谱系：子会话 1:1；父删除经 FK SET NULL 收敛（快照自洽）。 */
@Dao
public interface ConversationLineageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(ConversationLineageEntity entity);

    @Query("SELECT * FROM conversation_lineage WHERE child_conversation_id"
            + " = :childConversationId LIMIT 1")
    ConversationLineageEntity getByChild(String childConversationId);

    @Query("SELECT COUNT(*) FROM conversation_lineage")
    int countAll();
}
