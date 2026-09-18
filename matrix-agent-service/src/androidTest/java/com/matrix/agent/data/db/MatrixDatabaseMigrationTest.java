package com.matrix.agent.data.db;
import com.matrix.agent.host.di.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;

import androidx.room.testing.MigrationTestHelper;
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;

/**
 * Room Migration v1→v2 端到端验证——audit_event 加 userId 列 +
 * idx_audit_user_zone 索引。
 *
 * <p>R1 缓解:用 room-testing {@link MigrationTestHelper} 在 instrumented device 上:
 * <ol>
 *   <li>createDatabase(v1):建立 v1 schema(audit_event 无 userId 列);</li>
 *   <li>insert 历史格式行(无 userId 字段);</li>
 *   <li>runMigrationsAndValidate(v2, MIGRATION_1_2):执行迁移 + 校验 schema 与 2.json 一致;</li>
 *   <li>验证历史行 userId 默认 {@code ''}(Migration ALTER DEFAULT '' 生效);</li>
 *   <li>验证 idx_audit_user_zone 索引存在。</li>
 * </ol>
 *
 * <p>Migration 失败场景(磁盘满 / schema 损坏)由 AuditRuntimeGraph
 * catch 后退化 NoopAuditRepository,这里仅验证 happy path。
 */
@RunWith(AndroidJUnit4.class)
public final class MatrixDatabaseMigrationTest {
    private static final String TEST_DB_NAME = "migration-test.db";

    @Rule
    public MigrationTestHelper helper;

    public MatrixDatabaseMigrationTest() {
        // MigrationTestHelper 自动读取 room.schemaLocation 配置的 2.json 校验目标 schema。
        helper = new MigrationTestHelper(
                InstrumentationRegistry.getInstrumentation(),
                MatrixDatabase.class.getCanonicalName(),
                new FrameworkSQLiteOpenHelperFactory());
    }

    @After
    public void tearDown() throws IOException {
        // 删除测试 DB 文件(避免污染其他 case)
        ApplicationProvider.getApplicationContext().deleteDatabase(TEST_DB_NAME);
    }

    @Test
    public void migrate1To2AddsUserIdColumnAndIndex() throws IOException {
        // 1. 建立 v1 schema(无 userId 列)
        SupportSQLiteDatabase db = helper.createDatabase(TEST_DB_NAME, 1);
        // 历史格式行——所有原字段,requestId/type/actor/zone/happenedAtMs/payloadJson + id
        db.execSQL("INSERT INTO audit_event (id, requestId, type, actor, zone, happenedAtMs, "
                + "payloadJson) VALUES (1, 'req-legacy', 'TERMINAL', 'DRIVER', 'DRIVER', "
                + "1000, '{\"state\":\"SUCCEEDED\"}')");
        db.execSQL("INSERT INTO audit_event (id, requestId, type, actor, zone, happenedAtMs, "
                + "payloadJson) VALUES (2, 'req-legacy-2', 'TERMINAL', 'PASSENGER', 'PASSENGER', "
                + "2000, '{\"state\":\"FAILED\"}')");
        db.close();

        // 2. 执行迁移 + 校验 schema 与 v2.json 一致
        db = helper.runMigrationsAndValidate(TEST_DB_NAME, 2, true, MatrixDatabase.MIGRATION_1_2);

        // 3. 历史行 userId 应为默认 ''
        Cursor cursor = db.query("SELECT userId FROM audit_event WHERE id = 1");
        assertTrue("历史行必须存在", cursor.moveToFirst());
        assertEquals("Migration ALTER DEFAULT '' 必须让历史行 userId 为空串",
                "", cursor.getString(0));
        cursor.close();

        Cursor cursor2 = db.query("SELECT userId FROM audit_event WHERE id = 2");
        assertTrue("第二条历史行也必须存在", cursor2.moveToFirst());
        assertEquals("", cursor2.getString(0));
        cursor2.close();

        // 4. 新插入的行可显式写入 userId
        db.execSQL("INSERT INTO audit_event (id, requestId, type, actor, zone, happenedAtMs, "
                + "payloadJson, userId) VALUES (3, 'req-new', 'PRE_TOOL', 'DRIVER', 'DRIVER', "
                + "3000, '{\"tool\":\"vehicle.climate\"}', 'demo-driver')");

        // 5. idx_audit_user_zone 索引存在(sqlite_master 验证)
        Cursor idxCursor = db.query("SELECT name FROM sqlite_master WHERE type='index' "
                + "AND name='idx_audit_user_zone'");
        assertTrue("idx_audit_user_zone 索引必须存在", idxCursor.moveToFirst());
        idxCursor.close();

        db.close();
    }

    @Test
    public void migratedDatabaseSupportsQueryByUserZone() throws IOException {
        SupportSQLiteDatabase db = helper.createDatabase(TEST_DB_NAME, 1);
        db.close();

        db = helper.runMigrationsAndValidate(TEST_DB_NAME, 2, true, MatrixDatabase.MIGRATION_1_2);

        // 历史格式行 userId='' —— 主路径查询过滤不到
        db.execSQL("INSERT INTO audit_event (id, requestId, type, actor, zone, happenedAtMs, "
                + "payloadJson) VALUES (1, 'req-legacy', 'TERMINAL', 'DRIVER', 'DRIVER', "
                + "1000, '{}')");
        // 新行带 userId
        db.execSQL("INSERT INTO audit_event (id, requestId, type, actor, zone, happenedAtMs, "
                + "payloadJson, userId) VALUES (2, 'req-new', 'PRE_TOOL', 'DRIVER', 'DRIVER', "
                + "2000, '{}', 'demo-driver')");

        // queryByUserZone("demo-driver", "DRIVER") 应仅返回新行,过滤掉 userId='' 的历史行
        Cursor cursor = db.query("SELECT COUNT(*) FROM audit_event "
                + "WHERE userId = 'demo-driver' AND zone = 'DRIVER'");
        assertTrue(cursor.moveToFirst());
        assertEquals("仅新行 1 条匹配(userId='' 历史行过滤)", 1, cursor.getInt(0));
        cursor.close();

        db.close();
    }

    @Test
    public void migrate4To5CreatesCallerScopedTaskAndReplayTables() throws IOException {
        SupportSQLiteDatabase db = helper.createDatabase(TEST_DB_NAME, 4);
        db.close();

        db = helper.runMigrationsAndValidate(TEST_DB_NAME, 5, true, MatrixDatabase.MIGRATION_4_5);
        db.execSQL("INSERT INTO agent_task (taskId, ownerUid, ownerPackage, ownerUserId, "
                + "clientRequestId, clientSessionId, requestHash, state, lastSequence, safeText, "
                + "errorCode, createdAtMs, updatedAtMs, terminalAtMs) VALUES "
                + "('task-1', 10001, 'com.example', 0, 'request-1', 'session-1', 'hash', "
                + "0, 1, 'accepted', 0, 1, 1, 0)");
        db.execSQL("INSERT INTO agent_task_event (taskId, sequence, elapsedRealtimeMs, type, "
                + "state, safePayload) VALUES ('task-1', 1, 9, 1, 0, 'accepted')");
        db.execSQL("INSERT INTO agent_task_operation (taskId, clientOperationId, operationType, "
                + "requestHash, resultCode, acceptedSequence, taskState, createdAtMs) VALUES "
                + "('task-1', 'operation-1', 'cancel', 'hash', 0, 1, 0, 2)");

        Cursor eventCursor = db.query("SELECT safePayload FROM agent_task_event "
                + "WHERE taskId='task-1' AND sequence=1");
        assertTrue(eventCursor.moveToFirst());
        assertEquals("accepted", eventCursor.getString(0));
        eventCursor.close();

        Cursor uniqueIndex = db.query("SELECT name FROM sqlite_master WHERE type='index' "
                + "AND name='idx_agent_task_owner_request'");
        assertTrue("caller + client request must be uniquely indexed", uniqueIndex.moveToFirst());
        uniqueIndex.close();
        db.close();
    }

    @Test
    public void migrate5To6AddsEmptyReplayPayloadForLegacyTasks() throws IOException {
        SupportSQLiteDatabase db = helper.createDatabase(TEST_DB_NAME, 5);
        db.execSQL("INSERT INTO agent_task (taskId, ownerUid, ownerPackage, ownerUserId, "
                + "clientRequestId, clientSessionId, requestHash, state, lastSequence, safeText, "
                + "errorCode, createdAtMs, updatedAtMs, terminalAtMs) VALUES "
                + "('legacy-task', 10001, 'com.example', 0, 'request-1', 'session-1', 'hash', "
                + "3, 1, 'deferred', 0, 1, 1, 0)");
        db.close();

        db = helper.runMigrationsAndValidate(TEST_DB_NAME, 6, true,
                MatrixDatabase.MIGRATION_5_6);
        Cursor cursor = db.query("SELECT requestText FROM agent_task "
                + "WHERE taskId='legacy-task'");
        assertTrue(cursor.moveToFirst());
        assertEquals("", cursor.getString(0));
        cursor.close();
        db.close();
    }
}
