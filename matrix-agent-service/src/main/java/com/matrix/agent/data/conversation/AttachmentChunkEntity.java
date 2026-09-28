package com.matrix.agent.data.conversation;

import androidx.annotation.NonNull;
import androidx.room.*;

@Entity(tableName = "attachment_chunk", primaryKeys = {"attachmentId", "ordinal"},
        foreignKeys = @ForeignKey(entity = ConversationAttachmentEntity.class,
                parentColumns = "attachment_id", childColumns = "attachmentId", onDelete = ForeignKey.CASCADE))
public final class AttachmentChunkEntity {
    @NonNull public String attachmentId;
    public int ordinal;
    @NonNull public String contentVersion;
    public int startChar;
    public int endChar;
    @NonNull public String text;
}
