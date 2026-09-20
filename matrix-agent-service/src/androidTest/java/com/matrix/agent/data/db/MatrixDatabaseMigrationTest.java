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

    /**
     * v7 → v8（对话能力评估 v1.0 的 schema 地基）：三表加列 + titleOrigin 保守回填。
     * 回填语义是防覆盖的关键——非空历史标题标 USER，AUTO 标题永不覆盖它。
     */
    @Test
    public void migrate7To8AddsCapabilityColumnsAndBackfillsTitleOrigin() throws IOException {
        SupportSQLiteDatabase db = helper.createDatabase(TEST_DB_NAME, 7);
        // 会话两行：未命名（title NULL）与用户已命名
        db.execSQL("INSERT INTO conversation (conversation_id, owner_user_id, vehicle_zone, "
                + "title, created_at_ms, updated_at_ms, schema_version) VALUES "
                + "('conv-untitled', 'driver-1', 'DRIVER', NULL, 1000, 1000, 1)");
        db.execSQL("INSERT INTO conversation (conversation_id, owner_user_id, vehicle_zone, "
                + "title, created_at_ms, updated_at_ms, schema_version) VALUES "
                + "('conv-named', 'driver-1', 'DRIVER', '我的空调对话', 2000, 2000, 1)");
        // 消息与任务链接各一行（v7 列集）
        db.execSQL("INSERT INTO conversation_message (message_id, conversation_id, sequence_no, "
                + "role, status, channel, text, language_tag, conversation_task_id, "
                + "reply_to_message_id, failure_code, created_at_ms, updated_at_ms, "
                + "idempotency_key, schema_version) VALUES "
                + "('msg-1', 'conv-untitled', 1, 0, 2, 1, '把温度调到 24 度', 'zh-CN', "
                + "'task-1', NULL, 0, 1000, 1100, 'idem-1', 1)");
        db.execSQL("INSERT INTO conversation_task_link (conversation_task_id, "
                + "runtime_request_id, conversation_id, user_message_id, assistant_message_id, "
                + "read_only_hint, terminal_status, created_at_ms, started_at_ms, "
                + "terminal_at_ms) VALUES "
                + "('task-1', 'req-1', 'conv-untitled', 'msg-1', NULL, 0, NULL, "
                + "1000, NULL, NULL)");
        db.close();

        db = helper.runMigrationsAndValidate(TEST_DB_NAME, 8, true,
                MatrixDatabase.MIGRATION_7_8);

        // titleOrigin 回填：NULL 标题 → DEFAULT(0)，非空 → USER(2)
        Cursor origin = db.query("SELECT conversation_id, title_origin, pinned, "
                + "last_input_channel FROM conversation ORDER BY conversation_id");
        assertTrue(origin.moveToFirst());
        assertEquals("conv-named 行：非空标题保守标 USER", 2, origin.getInt(1));
        assertEquals(0, origin.getInt(2));
        assertEquals(0, origin.getInt(3));
        assertTrue(origin.moveToNext());
        assertEquals("conv-untitled 行：NULL 标题标 DEFAULT", 0, origin.getInt(1));
        origin.close();

        // 消息：input_kind 回填 INPUT_PRIMARY，steer 列 NULL
        Cursor message = db.query("SELECT input_kind, steer_host_user_message_id, "
                + "steer_delivery_state FROM conversation_message WHERE message_id='msg-1'");
        assertTrue(message.moveToFirst());
        assertEquals(0, message.getInt(0));
        assertTrue(message.isNull(1));
        assertTrue(message.isNull(2));
        message.close();

        // 任务链接：轨迹列存在且为 NULL（阶段 2 才有写入方）
        Cursor link = db.query("SELECT execution_trace_json, trace_projection_version "
                + "FROM conversation_task_link WHERE conversation_task_id='task-1'");
        assertTrue(link.moveToFirst());
        assertTrue(link.isNull(0));
        assertTrue(link.isNull(1));
        link.close();

        db.close();
    }

    /**
     * v8 → v9（阶段 3 用户组织）：三表落成 + 外键级联语义在设备上真实触发验证。
     */
    @Test
    public void migrate8To9CreatesOrganizationTablesWithCascades() throws IOException {
        SupportSQLiteDatabase db = helper.createDatabase(TEST_DB_NAME, 8);
        // 8.json 建表无 DEFAULT 子句（DEFAULT 只存在于迁移 ALTER 里）——显式带 v8 列
        db.execSQL("INSERT INTO conversation (conversation_id, owner_user_id, vehicle_zone, "
                + "title, created_at_ms, updated_at_ms, title_origin, pinned, "
                + "last_input_channel, schema_version) VALUES "
                + "('conv-1', 'driver-1', 'DRIVER', '父对话', 1000, 1000, 0, 0, 0, 1)");
        db.execSQL("INSERT INTO conversation (conversation_id, owner_user_id, vehicle_zone, "
                + "title, created_at_ms, updated_at_ms, title_origin, pinned, "
                + "last_input_channel, schema_version) VALUES "
                + "('conv-child', 'driver-1', 'DRIVER', NULL, 1000, 1000, 0, 0, 0, 1)");
        db.execSQL("INSERT INTO conversation_message (message_id, conversation_id, sequence_no, "
                + "role, status, channel, text, language_tag, conversation_task_id, "
                + "reply_to_message_id, failure_code, created_at_ms, updated_at_ms, "
                + "idempotency_key, input_kind, schema_version) VALUES "
                + "('msg-1', 'conv-1', 1, 0, 2, 1, '被引用的文本', 'zh-CN', NULL, NULL, "
                + "0, 1000, 1000, 'idem-1', 0, 1)");
        db.execSQL("INSERT INTO conversation_message (message_id, conversation_id, sequence_no, "
                + "role, status, channel, text, language_tag, conversation_task_id, "
                + "reply_to_message_id, failure_code, created_at_ms, updated_at_ms, "
                + "idempotency_key, input_kind, schema_version) VALUES "
                + "('msg-quote', 'conv-1', 2, 0, 2, 1, '引用它的回复', 'zh-CN', NULL, NULL, "
                + "0, 1000, 1000, 'idem-2', 0, 1)");
        db.close();

        db = helper.runMigrationsAndValidate(TEST_DB_NAME, 9, true,
                MatrixDatabase.MIGRATION_8_9);
        // 测试连接默认不开 FK 约束（生产由 Room onOpen 打开）——显式开启以验证
        // 迁移 SQL 声明的 CASCADE/SET NULL 行为本身
        db.execSQL("PRAGMA foreign_keys = ON");

        // 附属标记：复合主键 upsert 幂等
        db.execSQL("INSERT INTO conversation_message_annotation (message_id, owner_user_id, "
                + "favorite, user_note, created_at_ms, updated_at_ms) VALUES "
                + "('msg-1', 'driver-1', 1, '重要', 1000, 1000)");
        // 引用快照
        db.execSQL("INSERT INTO conversation_quote (message_id, quoted_message_id, "
                + "quote_snapshot, created_at_ms) VALUES "
                + "('msg-quote', 'msg-1', '被引用的文本', 1000)");
        // 分支谱系：子指向父
        db.execSQL("INSERT INTO conversation_lineage (child_conversation_id, "
                + "parent_conversation_id, fork_sequence_no, parent_title_at_fork, "
                + "seed_snapshot, seed_version, created_by_user, created_at_ms) VALUES "
                + "('conv-child', 'conv-1', 1, '父对话', '[]', 1, 'driver-1', 1000)");

        // 级联 1：删父会话 → lineage.parent 置 NULL（子自洽），父标题快照保留
        db.execSQL("DELETE FROM conversation WHERE conversation_id = 'conv-1'");
        Cursor lineage = db.query("SELECT parent_conversation_id, parent_title_at_fork, "
                + "seed_snapshot FROM conversation_lineage "
                + "WHERE child_conversation_id = 'conv-child'");
        assertTrue("父删除后子谱系仍自洽存在", lineage.moveToFirst());
        assertTrue("父引用 SET NULL", lineage.isNull(0));
        assertEquals("来源说明回退父标题快照", "父对话", lineage.getString(1));
        assertEquals("[]", lineage.getString(2));
        lineage.close();

        // 级联 2：消息行删除（生产经 clearForUsers 显式删消息）→ annotation 与 quote
        // 双侧 CASCADE（conv→message 无 DB 级联是既有设计：生产路径显式三表删除）
        db.execSQL("DELETE FROM conversation_message WHERE message_id = 'msg-1'");
        Cursor annotation = db.query("SELECT COUNT(*) FROM conversation_message_annotation");
        annotation.moveToFirst();
        assertEquals("消息删除后标记级联消失", 0, annotation.getInt(0));
        annotation.close();
        Cursor quote = db.query("SELECT COUNT(*) FROM conversation_quote");
        quote.moveToFirst();
        assertEquals("消息删除后引用级联消失（quoted 侧 CASCADE）", 0, quote.getInt(0));
        quote.close();

        // 级联 3：删子会话 → lineage CASCADE
        db.execSQL("DELETE FROM conversation WHERE conversation_id = 'conv-child'");
        Cursor child = db.query("SELECT COUNT(*) FROM conversation_lineage");
        child.moveToFirst();
        assertEquals(0, child.getInt(0));
        child.close();

        db.close();
    }
}
