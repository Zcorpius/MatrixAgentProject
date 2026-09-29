package com.matrix.agent.data.embedding;

import androidx.annotation.NonNull;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

public final class MemoryVectorMigration extends Migration {
    public MemoryVectorMigration() { super(17, 18); }
    @Override public void migrate(@NonNull SupportSQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS memory_vector (userId TEXT NOT NULL, zone TEXT NOT NULL, "
                + "layer TEXT NOT NULL, `key` TEXT NOT NULL, indexVersion INTEGER NOT NULL, "
                + "modelVersion TEXT NOT NULL, dimension INTEGER NOT NULL, digest TEXT NOT NULL, "
                + "epoch INTEGER NOT NULL, vector BLOB NOT NULL, PRIMARY KEY(userId, zone, layer, `key`), "
                + "FOREIGN KEY(userId, zone, layer, `key`) REFERENCES memory_record(userId, zone, layer, `key`) "
                + "ON UPDATE NO ACTION ON DELETE CASCADE)");
    }
}
