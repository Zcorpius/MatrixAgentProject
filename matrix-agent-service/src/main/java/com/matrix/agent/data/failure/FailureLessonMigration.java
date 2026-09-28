package com.matrix.agent.data.failure;

import androidx.annotation.NonNull;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

public final class FailureLessonMigration extends Migration {
    public FailureLessonMigration() { super(16, 17); }
    @Override public void migrate(@NonNull SupportSQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS failure_lesson (owner TEXT NOT NULL, zone TEXT NOT NULL, "
                + "sourceTask TEXT NOT NULL, epoch INTEGER NOT NULL, version INTEGER NOT NULL, "
                + "capability TEXT NOT NULL, code TEXT NOT NULL, evidenceRefs TEXT NOT NULL, "
                + "createdAt INTEGER NOT NULL, expiresAt INTEGER NOT NULL, "
                + "PRIMARY KEY(owner,zone,sourceTask,epoch))");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_failure_lesson_recall "
                + "ON failure_lesson(owner,zone,capability,expiresAt)");
    }
}
