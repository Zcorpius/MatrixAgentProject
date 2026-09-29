package com.matrix.agent.data.schedule;

import androidx.annotation.NonNull;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

/** Existing runs retain unknown channel details; no inferred delivery facts are backfilled. */
public final class ScheduleDeliveryMigration extends Migration {
    public ScheduleDeliveryMigration() { super(15, 16); }
    @Override public void migrate(@NonNull SupportSQLiteDatabase database) {
        database.execSQL("ALTER TABLE schedule_run ADD COLUMN deliveryFactsJson TEXT NOT NULL DEFAULT '{}' ");
    }
}
