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

    /** 置顶优先（展示元数据），其后按最近活动降序（评估 v1.0 §4.1）。 */
    @Query("SELECT * FROM conversation WHERE owner_user_id = :ownerUserId"
            + " AND (:includeArchived = 1 OR archived_at_ms IS NULL)"
            + " ORDER BY pinned DESC, updated_at_ms DESC LIMIT :limit")
    List<ConversationEntity> listByOwner(String ownerUserId, boolean includeArchived, int limit);

    /** 用户重命名：title_origin 直接置 USER(2)——此后 AUTO 标题永不覆盖。 */
    @Query("UPDATE conversation SET title = :title, title_origin = 2,"
            + " updated_at_ms = :updatedAtMs WHERE conversation_id = :conversationId")
    int rename(String conversationId, String title, long updatedAtMs);

    /** 自动标题比较交换：仅 DEFAULT(0) 行生效，写入 AUTO(1)——用户命名永不覆盖。 */
    @Query("UPDATE conversation SET title = :title, title_origin = 1,"
            + " updated_at_ms = :updatedAtMs WHERE conversation_id = :conversationId"
            + " AND title_origin = 0")
    int autoTitleIfDefault(String conversationId, String title, long updatedAtMs);

    /** 最近一次用户输入通道（复用冻结 CHANNEL_* 值）。 */
    @Query("UPDATE conversation SET last_input_channel = :channel WHERE conversation_id"
            + " = :conversationId")
    void touchLastInputChannel(String conversationId, int channel);

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
