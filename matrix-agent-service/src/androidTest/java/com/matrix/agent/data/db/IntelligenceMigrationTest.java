package com.matrix.agent.data.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;
import androidx.room.migration.Migration;
import androidx.room.testing.MigrationTestHelper;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.matrix.agent.data.conversation.AttachmentRetrievalMigration;
import com.matrix.agent.data.embedding.MemoryVectorMigration;
import com.matrix.agent.data.failure.FailureLessonMigration;
import com.matrix.agent.data.schedule.ResearchBudgetMigration;
import com.matrix.agent.data.schedule.ScheduleDeliveryMigration;
import com.matrix.agent.data.schedule.ScheduleMigration;
import java.io.IOException;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Frozen Room schemas and historical rows for every intelligence-layer migration. */
@RunWith(AndroidJUnit4.class)
public final class IntelligenceMigrationTest {
    private static final String DB = "intelligence-migration-test.db";

    @Rule public final MigrationTestHelper helper = new MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(), MatrixDatabase.class.getCanonicalName(),
            new FrameworkSQLiteOpenHelperFactory());

    @After public void deleteDatabase() {
        ApplicationProvider.<Context>getApplicationContext().deleteDatabase(DB);
    }

    private SupportSQLiteDatabase migrate(int to, Migration migration) throws IOException {
        return helper.runMigrationsAndValidate(DB, to, true, migration);
    }

    private static void assertScalar(SupportSQLiteDatabase db, String sql, String expected) {
        try (Cursor rows = db.query(sql)) {
            assertTrue(sql, rows.moveToFirst());
            assertEquals(sql, expected, rows.getString(0));
        }
    }

    private static void assertForeignKeysClean(SupportSQLiteDatabase db) {
        try (Cursor violations = db.query("PRAGMA foreign_key_check")) {
            assertFalse(violations.moveToFirst());
        }
    }

    private static void insertMemory(SupportSQLiteDatabase db) {
        db.execSQL("INSERT INTO memory_record(userId,zone,layer,`key`,value,score,capturedAtMs) "
                + "VALUES('legacy-user','driver','semantic','fact.topic','历史记忆',0.8,1234)");
    }

    private static void insertRun(SupportSQLiteDatabase db) {
        db.execSQL("INSERT INTO schedule_run(runId,scheduleId,occurrenceKey,ownerUid,dataEpoch,"
                + "definitionRevision,timingRevision,dispatchGeneration,dispatchClaimed,actor,zone,"
                + "title,specJson,authorizationJson,scheduledAt,expiresAt,state,deliveryStatus,"
                + "speechClaimed,triggerKind,result,reason,runtimeRequestId,requestHash,bootId,"
                + "activeMillis,toolCalls,updatedAt) VALUES "
                + "('historical-run','schedule-1','occurrence-1',1000,2,1,1,1,0,'DRIVER',"
                + "'DRIVER','历史任务','{}','{}',100,1000,2,0,0,'ALARM','','',"
                + "'request-1','digest','boot-1',321,7,900)");
    }

    @Test public void scheduleSchemaFrom14To15PreservesMemory() throws IOException {
        SupportSQLiteDatabase db = helper.createDatabase(DB, 14);
        insertMemory(db);
        db.close();
        db = migrate(15, new ScheduleMigration());
        assertScalar(db, "SELECT value FROM memory_record WHERE `key`='fact.topic'", "历史记忆");
        insertRun(db);
        assertScalar(db, "SELECT toolCalls FROM schedule_run WHERE runId='historical-run'", "7");
        db.close();
    }

    @Test public void deliveryFactsFrom15To16DefaultWithoutInventedEvidence() throws IOException {
        SupportSQLiteDatabase db = helper.createDatabase(DB, 15);
        insertRun(db);
        db.close();
        db = migrate(16, new ScheduleDeliveryMigration());
        assertScalar(db, "SELECT deliveryFactsJson FROM schedule_run WHERE runId='historical-run'", "{}");
        assertScalar(db, "SELECT activeMillis FROM schedule_run WHERE runId='historical-run'", "321");
        db.close();
    }

    @Test public void failureLessonFrom16To17PreservesSourceAndIndexesRecall() throws IOException {
        SupportSQLiteDatabase db = helper.createDatabase(DB, 16);
        insertMemory(db);
        db.close();
        db = migrate(17, new FailureLessonMigration());
        assertScalar(db, "SELECT value FROM memory_record WHERE `key`='fact.topic'", "历史记忆");
        db.execSQL("INSERT INTO failure_lesson(owner,zone,sourceTask,epoch,version,capability,"
                + "code,evidenceRefs,createdAt,expiresAt) VALUES "
                + "('legacy-user','driver','task-1',2,1,'vehicle.read','FAILED','[]',100,200)");
        assertScalar(db, "SELECT name FROM sqlite_master WHERE type='index' "
                + "AND name='idx_failure_lesson_recall'", "idx_failure_lesson_recall");
        assertForeignKeysClean(db);
        db.close();
    }

    @Test public void memoryVectorFrom17To18ReferencesHistoricalMemory() throws IOException {
        SupportSQLiteDatabase db = helper.createDatabase(DB, 17);
        insertMemory(db);
        db.close();
        db = migrate(18, new MemoryVectorMigration());
        db.execSQL("PRAGMA foreign_keys=ON");
        db.execSQL("INSERT INTO memory_vector(userId,zone,layer,`key`,indexVersion,modelVersion,"
                + "dimension,digest,epoch,vector) VALUES "
                + "('legacy-user','driver','semantic','fact.topic',1,'pinned',2,'digest',2,X'00000000')");
        assertForeignKeysClean(db);
        db.execSQL("DELETE FROM memory_record WHERE `key`='fact.topic'");
        assertScalar(db, "SELECT count(*) FROM memory_vector", "0");
        db.close();
    }

    @Test public void attachmentChunksFrom18To19KeepLegacyExtractAndCascade() throws IOException {
        SupportSQLiteDatabase db = helper.createDatabase(DB, 18);
        db.execSQL("INSERT INTO conversation_attachment(attachment_id,owner_user_id,vehicle_zone,"
                + "conversation_id,source_kind,mime_type,safe_display_name,byte_size,state,error_code,"
                + "extracted_text,extracted_chars,ordinal,created_at_ms,client_operation_id) VALUES "
                + "('old-attachment','legacy-user','driver','conversation-1',0,'text/plain',"
                + "'legacy.txt',6,2,0,'历史正文',4,0,100,'operation-1')");
        db.close();
        db = migrate(19, new AttachmentRetrievalMigration());
        assertScalar(db, "SELECT extracted_text FROM conversation_attachment WHERE attachment_id='old-attachment'", "历史正文");
        try (Cursor row = db.query("SELECT retrieval_manifest FROM conversation_attachment WHERE attachment_id='old-attachment'")) {
            assertTrue(row.moveToFirst());
            assertTrue(row.isNull(0));
        }
        db.execSQL("PRAGMA foreign_keys=ON");
        db.execSQL("INSERT INTO attachment_chunk(attachmentId,ordinal,contentVersion,startChar,endChar,text) "
                + "VALUES('old-attachment',0,'digest',0,4,'历史正文')");
        assertForeignKeysClean(db);
        db.execSQL("DELETE FROM conversation_attachment WHERE attachment_id='old-attachment'");
        assertScalar(db, "SELECT count(*) FROM attachment_chunk", "0");
        db.close();
    }

    @Test public void researchBudgetFrom19To20DefaultsWithoutResettingTools() throws IOException {
        SupportSQLiteDatabase db = helper.createDatabase(DB, 19);
        insertRun(db);
        db.close();
        db = migrate(20, new ResearchBudgetMigration());
        assertScalar(db, "SELECT modelCalls FROM schedule_run WHERE runId='historical-run'", "0");
        assertScalar(db, "SELECT toolCalls FROM schedule_run WHERE runId='historical-run'", "7");
        db.execSQL("UPDATE schedule_run SET modelCalls=40 WHERE runId='historical-run'");
        assertScalar(db, "SELECT modelCalls FROM schedule_run WHERE runId='historical-run'", "40");
        db.close();
    }
}
