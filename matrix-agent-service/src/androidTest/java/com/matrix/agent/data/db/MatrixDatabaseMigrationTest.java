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

    /** v9 → v10：debug trace 迁移必须与 Room 导出的 v10 schema 完整对齐。 */
    @Test
    public void migrate9To10CreatesEncryptedDebugTraceProjectionTable() throws IOException {
        SupportSQLiteDatabase db = helper.createDatabase(TEST_DB_NAME, 9);
        db.close();

        db = helper.runMigrationsAndValidate(TEST_DB_NAME, 10, true,
                MatrixDatabase.MIGRATION_9_10);
        db.execSQL("INSERT INTO debug_trace_event (event_id, runtime_request_id, "
                + "conversation_task_id, conversation_id, host_user_message_id, "
                + "event_sequence, timestamp_ms, phase, trace_id, part_index, part_count, "
                + "payload, schema_version) VALUES "
                + "('dt-test:0', 'req-1', 'ct-1', 'conv-1', 'msg-1', 1, 1000, "
                + "'MODEL_REASONING', 'dt-test', 0, 1, 'safe', 1)");
        Cursor cursor = db.query("SELECT payload FROM debug_trace_event WHERE event_id='dt-test:0'");
        assertTrue(cursor.moveToFirst());
        assertEquals("safe", cursor.getString(0));
        cursor.close();
        db.close();
    }

    /**
     * v10 → v11（输入交互增强 I4）：草稿两表迁移与 Room 导出的 v11 schema 对齐；
     * 两表可写、复合主键生效（同 scope 同会话仅一行 REPLACE 语义由 DAO 承担）。
     */
    @Test
    public void migrate10To11CreatesDraftAndTombstoneTables() throws IOException {
        SupportSQLiteDatabase db = helper.createDatabase(TEST_DB_NAME, 10);
        db.close();

        db = helper.runMigrationsAndValidate(TEST_DB_NAME, 11, true,
                MatrixDatabase.MIGRATION_10_11);
        db.execSQL("INSERT INTO conversation_draft (owner_user_id, vehicle_zone, "
                + "conversation_id, draft_instance_id, revision, text, selection_start, "
                + "selection_end, updated_at_ms) VALUES "
                + "('demo-driver', 'DRIVER', 'conv-1', 'inst-1', 3, '草稿', 0, 2, 1000)");
        db.execSQL("INSERT INTO conversation_consumed_draft (owner_user_id, vehicle_zone, "
                + "conversation_id, draft_instance_id, consumed_at_ms) VALUES "
                + "('demo-driver', 'DRIVER', 'conv-1', 'inst-0', 999)");
        Cursor draft = db.query("SELECT revision, text FROM conversation_draft "
                + "WHERE conversation_id='conv-1'");
        assertTrue(draft.moveToFirst());
        assertEquals(3, draft.getLong(0));
        assertEquals("草稿", draft.getString(1));
        draft.close();
        Cursor tombstone = db.query("SELECT consumed_at_ms FROM conversation_consumed_draft "
                + "WHERE draft_instance_id='inst-0'");
        assertTrue(tombstone.moveToFirst());
        assertEquals(999, tombstone.getLong(0));
        tombstone.close();
        db.close();
    }

    /**
     * v11 → v12（输入交互增强 I6/I5 Phase 2）：conversation_attachment 表 +
     * conversation_task_link 的 5 个 ModelExecutionSnapshot 列。
     */
    @Test
    public void migrate11To12CreatesAttachmentTableAndModelSnapshotColumns()
            throws IOException {
        SupportSQLiteDatabase db = helper.createDatabase(TEST_DB_NAME, 11);
        db.close();

        db = helper.runMigrationsAndValidate(TEST_DB_NAME, 12, true,
                MatrixDatabase.MIGRATION_11_12);
        db.execSQL("INSERT INTO conversation_attachment (attachment_id, owner_user_id, "
                + "vehicle_zone, conversation_id, source_kind, mime_type, safe_display_name, "
                + "byte_size, state, error_code, extracted_text, extracted_chars, "
                + "linked_message_id, ordinal, created_at_ms, client_operation_id) VALUES "
                + "('att-1', 'demo-driver', 'DRIVER', 'conv-1', 0, 'text/plain', "
                + "'notes.txt', 100, 1, 0, 'Hello', 5, NULL, 0, 1000, 'op-1')");
        db.execSQL("UPDATE conversation_attachment SET linked_message_id='msg-1', ordinal=0 "
                + "WHERE attachment_id='att-1'");
        Cursor att = db.query("SELECT state, extracted_text FROM conversation_attachment "
                + "WHERE attachment_id='att-1'");
        assertTrue(att.moveToFirst());
        assertEquals(1, att.getInt(0));
        assertEquals("Hello", att.getString(1));
        att.close();

        Cursor snapshot = db.query("SELECT model_provider_id, model_id, model_backend, "
                + "config_generation, config_fingerprint FROM conversation_task_link LIMIT 1");
        assertTrue("空 link 行的快照列为 null", !snapshot.moveToFirst()
                || (snapshot.isNull(0) && snapshot.isNull(4)));
        snapshot.close();
        db.close();
    }

    @Test
    public void migrate12To13ScopesAttachmentOperationUniquenessByOwnerAndZone()
            throws IOException {
        SupportSQLiteDatabase db = helper.createDatabase(TEST_DB_NAME, 12);
        db.execSQL("INSERT INTO conversation_attachment (attachment_id, owner_user_id, "
                + "vehicle_zone, conversation_id, source_kind, mime_type, safe_display_name, "
                + "byte_size, state, error_code, extracted_text, extracted_chars, "
                + "linked_message_id, ordinal, created_at_ms, client_operation_id) VALUES "
                + "('att-driver', 'driver-a', 'DRIVER', 'conv-a', 0, 'text/plain', "
                + "'a.txt', 1, 1, 0, 'A', 1, NULL, 0, 1, 'op-shared')");
        db.close();

        db = helper.runMigrationsAndValidate(TEST_DB_NAME, 13, true,
                MatrixDatabase.MIGRATION_12_13);
        db.execSQL("INSERT INTO conversation_attachment (attachment_id, owner_user_id, "
                + "vehicle_zone, conversation_id, source_kind, mime_type, safe_display_name, "
                + "byte_size, state, error_code, extracted_text, extracted_chars, "
                + "linked_message_id, ordinal, created_at_ms, client_operation_id) VALUES "
                + "('att-passenger', 'driver-a', 'PASSENGER', 'conv-b', 0, 'text/plain', "
                + "'b.txt', 1, 1, 0, 'B', 1, NULL, 0, 2, 'op-shared')");

        Cursor index = db.query("SELECT name FROM sqlite_master WHERE type='index' "
                + "AND name='uq_attachment_owner_zone_operation'");
        assertTrue("迁移后必须有 owner/zone 复合唯一索引", index.moveToFirst());
        index.close();
        Cursor count = db.query("SELECT COUNT(*) FROM conversation_attachment "
                + "WHERE client_operation_id='op-shared'");
        assertTrue(count.moveToFirst());
        assertEquals("不同隔离域可安全使用相同 operationId", 2, count.getInt(0));
        count.close();
        db.close();
    }
}
