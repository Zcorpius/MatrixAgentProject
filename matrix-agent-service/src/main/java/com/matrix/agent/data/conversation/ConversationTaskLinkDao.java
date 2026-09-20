package com.matrix.agent.data.conversation;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface ConversationTaskLinkDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(ConversationTaskLinkEntity entity);

    @Query("SELECT * FROM conversation_task_link WHERE conversation_task_id = :conversationTaskId")
    ConversationTaskLinkEntity getById(String conversationTaskId);

    @Query("SELECT * FROM conversation_task_link WHERE runtime_request_id = :runtimeRequestId")
    ConversationTaskLinkEntity getByRuntimeRequestId(String runtimeRequestId);

    @Query("SELECT * FROM conversation_task_link WHERE user_message_id = :userMessageId")
    ConversationTaskLinkEntity getByUserMessageId(String userMessageId);

    /** 恢复对账的扫描目标：一切未终态关联。 */
    @Query("SELECT * FROM conversation_task_link WHERE terminal_status IS NULL")
    List<ConversationTaskLinkEntity> loadNonTerminal();

    /** appendMessage 判据：该 conversation 存在已派发未终态的任务。 */
    @Query("SELECT * FROM conversation_task_link WHERE conversation_id = :conversationId"
            + " AND terminal_status IS NULL AND started_at_ms IS NOT NULL LIMIT 1")
    ConversationTaskLinkEntity findRunning(String conversationId);

    @Query("UPDATE conversation_task_link SET started_at_ms = :startedAtMs"
            + " WHERE conversation_task_id = :conversationTaskId")
    void markStarted(String conversationTaskId, long startedAtMs);

    @Query("UPDATE conversation_task_link SET terminal_status = :terminalStatus,"
            + " assistant_message_id = :assistantMessageId, terminal_at_ms = :terminalAtMs"
            + " WHERE conversation_task_id = :conversationTaskId")
    void writeTerminal(String conversationTaskId, int terminalStatus,
            String assistantMessageId, long terminalAtMs);

    /** 轨迹投影写入（终态同事务调用；幂等——重复写同值无害）。 */
    @Query("UPDATE conversation_task_link SET execution_trace_json = :traceJson,"
            + " trace_projection_version = :version WHERE conversation_task_id"
            + " = :conversationTaskId")
    void updateTrace(String conversationTaskId, String traceJson, int version);

    @Query("DELETE FROM conversation_task_link WHERE conversation_id IN"
            + " (SELECT conversation_id FROM conversation WHERE owner_user_id IN (:userIds))")
    int deleteByOwnerUsers(List<String> userIds);

    @Query("SELECT COUNT(*) FROM conversation_task_link")
    int countAll();
}
