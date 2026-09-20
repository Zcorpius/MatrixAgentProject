package com.matrix.agent.data.conversation;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

/** 引用回复：1:1 于发起消息；quoted 索引服务"谁引用了我"类回查。 */
@Dao
public interface ConversationQuoteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(ConversationQuoteEntity entity);

    @Query("SELECT * FROM conversation_quote WHERE message_id = :messageId LIMIT 1")
    ConversationQuoteEntity getByQuotingMessage(String messageId);

    @Query("SELECT COUNT(*) FROM conversation_quote")
    int countAll();
}
