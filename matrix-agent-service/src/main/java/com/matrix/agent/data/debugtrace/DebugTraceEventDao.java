package com.matrix.agent.data.debugtrace;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface DebugTraceEventDao {

    /** retry / Binder 重连不会复制同一 trace 分片。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insertIgnore(List<DebugTraceEventEntity> entities);

    @Query("SELECT * FROM debug_trace_event WHERE host_user_message_id = :hostUserMessageId "
            + "ORDER BY timestamp_ms ASC, event_sequence ASC, part_index ASC LIMIT :limit")
    List<DebugTraceEventEntity> listForHostMessage(String hostUserMessageId, int limit);

    @Query("DELETE FROM debug_trace_event WHERE conversation_id IN "
            + "(SELECT conversation_id FROM conversation WHERE owner_user_id IN (:userIds))")
    int deleteByOwnerUsers(List<String> userIds);

    @Query("DELETE FROM debug_trace_event")
    int deleteAll();
}
