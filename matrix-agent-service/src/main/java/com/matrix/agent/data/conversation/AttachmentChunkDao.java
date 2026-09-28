package com.matrix.agent.data.conversation;

import androidx.room.*;
import java.util.List;

@Dao
public interface AttachmentChunkDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) void insert(List<AttachmentChunkEntity> chunks);
    @Query("SELECT c.* FROM attachment_chunk c INNER JOIN conversation_attachment a ON a.attachment_id=c.attachmentId "
            + "WHERE a.owner_user_id=:owner AND a.vehicle_zone=:zone AND a.conversation_id=:conversation "
            + "AND a.attachment_id IN (:ids) AND a.state=1 ORDER BY c.attachmentId,c.ordinal LIMIT 16384")
    List<AttachmentChunkEntity> load(String owner, String zone, String conversation, List<String> ids);
    @Query("SELECT COALESCE(SUM(length(CAST(c.text AS BLOB))),0) FROM attachment_chunk c "
            + "INNER JOIN conversation_attachment a ON a.attachment_id=c.attachmentId "
            + "WHERE a.owner_user_id=:owner AND a.vehicle_zone=:zone")
    long storageBytes(String owner, String zone);
    @Query("SELECT COUNT(*) FROM conversation_attachment WHERE owner_user_id=:owner AND vehicle_zone=:zone")
    int attachmentCount(String owner, String zone);
    @Query("DELETE FROM attachment_chunk WHERE attachmentId=:id") void delete(String id);
}
