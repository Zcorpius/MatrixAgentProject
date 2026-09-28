package com.matrix.agent.data.schedule;

/** Persist the parent model-call budget so process recovery cannot grant additional calls. */
public final class ResearchBudgetMigration extends androidx.room.migration.Migration {
    public ResearchBudgetMigration() { super(19, 20); }
    @Override public void migrate(androidx.sqlite.db.SupportSQLiteDatabase database) {
        database.execSQL("ALTER TABLE schedule_run ADD COLUMN modelCalls INTEGER NOT NULL DEFAULT 0");
    }
}
