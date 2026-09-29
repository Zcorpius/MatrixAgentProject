package com.matrix.agent.data.conversation;

import androidx.annotation.NonNull;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

public final class AttachmentRetrievalMigration extends Migration {
    public AttachmentRetrievalMigration() { super(18,19); }
    @Override public void migrate(@NonNull SupportSQLiteDatabase db) {
        db.execSQL("ALTER TABLE conversation_attachment ADD COLUMN retrieval_manifest TEXT");
        db.execSQL("CREATE TABLE IF NOT EXISTS attachment_chunk (attachmentId TEXT NOT NULL, ordinal INTEGER NOT NULL, "
                + "contentVersion TEXT NOT NULL, startChar INTEGER NOT NULL, endChar INTEGER NOT NULL, text TEXT NOT NULL, "
                + "PRIMARY KEY(attachmentId,ordinal), FOREIGN KEY(attachmentId) REFERENCES conversation_attachment(attachment_id) "
                + "ON UPDATE NO ACTION ON DELETE CASCADE)");
        // Legacy attachments retain their original 16k text. Retrieval chunks are materialized lazily, without pretending the missing tail exists.
    }
}
