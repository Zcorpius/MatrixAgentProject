package com.matrix.agent.data.debugtrace;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

/** 调试轨迹持久化 DAO：按宿主消息读取 + 启动清理 + 计数（有界写入用）。 */
@Dao
public interface DebugTraceEventRecordDao {

    @Insert
    void insert(DebugTraceEventRecordEntity entity);

    /** 按宿主消息 + 任务升序读取（内嵌面板历史恢复）。 */
    @Query("SELECT * FROM debug_trace_event_record WHERE host_user_message_id"
            + " = :hostUserMessageId AND conversation_task_id = :conversationTaskId"
            + " ORDER BY event_sequence ASC, part_index ASC LIMIT :limit")
    List<DebugTraceEventRecordEntity> loadForHostMessage(String hostUserMessageId,
            String conversationTaskId, int limit);

    /** 同 task 已有事件数（有界写入判定）。 */
    @Query("SELECT COUNT(*) FROM debug_trace_event_record"
            + " WHERE conversation_task_id = :conversationTaskId")
    int countForTask(String conversationTaskId);

    /** debugTraceUi=false 时 Host 启动清除全表（契约 7）。 */
    @Query("DELETE FROM debug_trace_event_record")
    void clearAll();
}
