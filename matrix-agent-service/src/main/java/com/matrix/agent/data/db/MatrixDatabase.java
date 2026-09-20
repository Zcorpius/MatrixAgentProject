package com.matrix.agent.data.db;

import com.matrix.agent.identity.AgentRequest;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteOpenHelper;

import com.matrix.agent.platform.MasterKeyProvider;

import net.zetetic.database.sqlcipher.SupportOpenHelperFactory;

import java.util.Arrays;

/**
 * Room + SQLCipher 加密数据库入口。
 *
 * <p>version=2——audit_event 加 userId 列 + idx_audit_user_zone
 * 索引,配合 {@link AuditEventDao#deleteByUserZone(String, String)} 实现 4 表原子 clear。
 *
 * <p>SQLCipher Android SupportOpenHelperFactory 注入 byte[] passphrase——passphrase 由 MasterKeyProvider
 * 提供(从 AndroidKeyStore 取主密钥解密本地缓存)。
 *
 * <p>passphrase 缺失/null/异常时**必须抛 IllegalStateException**,
 * 让 AuditRuntimeGraph 捕获后退化为 NoopAuditRepository。
 * 绝不静默回退到 builder.build()——否则会构建明文 Room DB,审计数据悄然落明文。
 *
 * <p>单例 + WAL 模式默认开启;引入 batch 后可调 JournalMode。
 *
 * <p>显式声明 {@code JournalMode.WRITE_AHEAD_LOGGING}——Room 2.7
 * 在 API 16+ 默认开 WAL,但某些 OEM ROM 关闭 SQLite WAL 影响并发读写(主路径 LLM 调用
 * 期间后台 audit insert / memory query 并行);显式 setJournalMode 锁定行为,同时
 * openHelperFactory 内通过 {@link SupportSQLiteOpenHelper}Callback {@code onOpen} 调
 * {@code enableWriteAheadLogging} 兜底,保证实际 SQLCipher 连接也走 WAL。
 *
 * <p>passphrase char[] 转 byte[] 后立即 {@link Arrays#fill}
 * 清零 char[](MatrixDatabase 不再使用 char[],只用 byte[] 给 SupportFactory)。
 * byte[] 不能清零——SupportFactory 在进程生命周期内会持续用它(WAL checkpoint / reopen),
 * 这是 SQLCipher 4.x 的固有限制;passphrase 实际保护由 AndroidKeyStore + 进程隔离兜底。
 *
 * <p>使用 {@code sqlcipher-android}（不再使用 EOL 的
 * {@code android-database-sqlcipher}）。该产物的 native library 已支持 Android 15
 * 16 KB page-size 设备；所有 ABI 由 AAR 随 APK 一并打包。
 */
@Database(
        entities = {
                TrajectoryEntity.class,
                SessionHistoryEntity.class,
                MemoryRecordEntity.class,
                AuditEventEntity.class,
                ModelDownloadEntity.class,
                AgentTaskEntity.class,
                AgentTaskEventEntity.class,
                AgentTaskOperationEntity.class,
                com.matrix.agent.data.conversation.ConversationEntity.class,
                com.matrix.agent.data.conversation.ConversationMessageEntity.class,
                com.matrix.agent.data.conversation.ConversationTaskLinkEntity.class
        },
        version = 7,
        exportSchema = true
)
public abstract class MatrixDatabase extends RoomDatabase {
    private static final String TAG = "MatrixAgent";
    private static final String DB_NAME = "matrix_agent.db";
    private static volatile MatrixDatabase instance;

    public abstract TrajectoryDao trajectoryDao();
    public abstract SessionHistoryDao sessionHistoryDao();
    public abstract MemoryRecordDao memoryRecordDao();
    public abstract AuditEventDao auditEventDao();
    public abstract ModelDownloadDao modelDownloadDao();
    public abstract AgentTaskDao agentTaskDao();
    public abstract com.matrix.agent.data.conversation.ConversationDao conversationDao();
    public abstract com.matrix.agent.data.conversation.ConversationMessageDao conversationMessageDao();
    public abstract com.matrix.agent.data.conversation.ConversationTaskLinkDao conversationTaskLinkDao();

    /**
     * schema v1 → v2 迁移——audit_event 加 userId 列 + idx_audit_user_zone 索引。
     *
     * <p>历史写入的 audit_event 行 userId 默认 {@code ''}——无法回填
     * (无 RequestId → AgentRequest 映射);按主路径 queryByUserZone 过滤不到,但仍按
     * requestId 可查,可接受(审计溯源按 requestId 是主路径)。
     *
     * <p>R1 缓解:Migration 仅 1 条 ALTER + 1 条 CREATE INDEX,语句最小化——
     * 失败时 Room 抛 IllegalStateException,AuditRuntimeGraph
     * catch 后退化 NoopAuditRepository(已有路径)。
     */
    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE audit_event ADD COLUMN userId TEXT NOT NULL DEFAULT ''");
            db.execSQL("CREATE INDEX IF NOT EXISTS `idx_audit_user_zone` "
                    + "ON `audit_event` (`userId`, `zone`)");
        }
    };

    /**
     * schema v2 → v3 迁移——audit_event 加 requestEpoch 列。
     *
     * <p>用 happenedAtMs(毫秒时间戳)与 staleEpoch(MemoryStore 版本号 1/2/3)
     * 比较 → 永远 false,stale gate 失效。改加 requestEpoch 字段(由 AgentEngine 5 处 recordXxx 透传
     * request.getEpoch()),isStale 用 epoch 比较。
     *
     * <p>历史行 requestEpoch 默认 0——0 表示"未透传 / 老数据",
     * 不参与 epoch gate(保留原行为,不清空老 audit_event)。
     */
    public static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE audit_event ADD COLUMN requestEpoch INTEGER NOT NULL DEFAULT 0");
        }
    };

    /**
     * 端侧模型下载管理:schema v3 → v4 迁移——新建 model_download 表。
     *
     * <p>记录模型下载进度/状态/校验信息。modelName 既是主键也是
     * {@code filesDir/models/mnn/<modelName>/} 的目录名。
     */
    public static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `model_download` ("
                    + "`modelName` TEXT NOT NULL PRIMARY KEY, "
                    + "`displayName` TEXT, "
                    + "`sourceRepo` TEXT, "
                    + "`marketUrl` TEXT, "
                    + "`totalBytes` INTEGER NOT NULL DEFAULT 0, "
                    + "`downloadedBytes` INTEGER NOT NULL DEFAULT 0, "
                    + "`status` TEXT, "
                    + "`sha256` TEXT, "
                    + "`createdAt` INTEGER NOT NULL DEFAULT 0, "
                    + "`updatedAt` INTEGER NOT NULL DEFAULT 0)");
        }
    };

    /** v4 → v5: durable task headers, replayable safe events and operation idempotency. */
    public static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS agent_task ("
                    + "taskId TEXT NOT NULL PRIMARY KEY, ownerUid INTEGER NOT NULL, "
                    + "ownerPackage TEXT NOT NULL, ownerUserId INTEGER NOT NULL, "
                    + "clientRequestId TEXT NOT NULL, clientSessionId TEXT NOT NULL, "
                    + "requestHash TEXT NOT NULL, state INTEGER NOT NULL, "
                    + "lastSequence INTEGER NOT NULL, safeText TEXT NOT NULL, "
                    + "errorCode INTEGER NOT NULL, pendingConfirmationId TEXT, "
                    + "createdAtMs INTEGER NOT NULL, updatedAtMs INTEGER NOT NULL, "
                    + "terminalAtMs INTEGER NOT NULL)");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_agent_task_owner_request "
                    + "ON agent_task (ownerUid, clientRequestId)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_agent_task_owner_updated "
                    + "ON agent_task (ownerUid, updatedAtMs)");
            db.execSQL("CREATE TABLE IF NOT EXISTS agent_task_event ("
                    + "taskId TEXT NOT NULL, sequence INTEGER NOT NULL, "
                    + "elapsedRealtimeMs INTEGER NOT NULL, type INTEGER NOT NULL, "
                    + "state INTEGER NOT NULL, safePayload TEXT NOT NULL, "
                    + "PRIMARY KEY(taskId, sequence))");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_agent_task_event_replay "
                    + "ON agent_task_event (taskId, sequence)");
            db.execSQL("CREATE TABLE IF NOT EXISTS agent_task_operation ("
                    + "taskId TEXT NOT NULL, clientOperationId TEXT NOT NULL, "
                    + "operationType TEXT NOT NULL, requestHash TEXT NOT NULL, "
                    + "resultCode INTEGER NOT NULL, acceptedSequence INTEGER NOT NULL, "
                    + "taskState INTEGER NOT NULL, createdAtMs INTEGER NOT NULL, "
                    + "PRIMARY KEY(taskId, clientOperationId))");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_agent_task_operation_time "
                    + "ON agent_task_operation (taskId, createdAtMs)");
        }
    };

    /** v5 → v6: encrypted-at-rest replay payload for explicitly deferred tasks. */
    public static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE agent_task ADD COLUMN requestText TEXT NOT NULL DEFAULT ''");
        }
    };

    /**
     * v6 → v7：对话域三表（设计文档 §5.2）。SQL 与 @Entity 注解逐列对齐——
     * Room 校验以实体派生 schema 为准，列名/类型/NOT NULL/索引名任何偏差都会在
     * 首次打开时抛 IllegalStateException（fail-closed，不静默重建）。
     */
    public static final Migration MIGRATION_6_7 = new Migration(6, 7) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `conversation` ("
                    + "`conversation_id` TEXT NOT NULL, "
                    + "`owner_user_id` TEXT NOT NULL, "
                    + "`vehicle_zone` TEXT NOT NULL, "
                    + "`title` TEXT, "
                    + "`created_at_ms` INTEGER NOT NULL, "
                    + "`updated_at_ms` INTEGER NOT NULL, "
                    + "`archived_at_ms` INTEGER, "
                    + "`schema_version` INTEGER NOT NULL, "
                    + "PRIMARY KEY(`conversation_id`))");
            db.execSQL("CREATE INDEX IF NOT EXISTS `idx_conversation_owner_zone` "
                    + "ON `conversation` (`owner_user_id`, `vehicle_zone`)");

            db.execSQL("CREATE TABLE IF NOT EXISTS `conversation_message` ("
                    + "`message_id` TEXT NOT NULL, "
                    + "`conversation_id` TEXT NOT NULL, "
                    + "`sequence_no` INTEGER NOT NULL, "
                    + "`role` INTEGER NOT NULL, "
                    + "`status` INTEGER NOT NULL, "
                    + "`channel` INTEGER, "
                    + "`text` TEXT NOT NULL, "
                    + "`language_tag` TEXT, "
                    + "`conversation_task_id` TEXT, "
                    + "`reply_to_message_id` TEXT, "
                    + "`failure_code` INTEGER NOT NULL, "
                    + "`created_at_ms` INTEGER NOT NULL, "
                    + "`updated_at_ms` INTEGER NOT NULL, "
                    + "`idempotency_key` TEXT, "
                    + "`schema_version` INTEGER NOT NULL, "
                    + "PRIMARY KEY(`message_id`))");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `uq_conversation_message_seq` "
                    + "ON `conversation_message` (`conversation_id`, `sequence_no`)");
            db.execSQL("CREATE INDEX IF NOT EXISTS `idx_conversation_message_task` "
                    + "ON `conversation_message` (`conversation_task_id`)");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `uq_conversation_message_idem` "
                    + "ON `conversation_message` (`idempotency_key`)");

            db.execSQL("CREATE TABLE IF NOT EXISTS `conversation_task_link` ("
                    + "`conversation_task_id` TEXT NOT NULL, "
                    + "`runtime_request_id` TEXT NOT NULL, "
                    + "`conversation_id` TEXT NOT NULL, "
                    + "`user_message_id` TEXT NOT NULL, "
                    + "`assistant_message_id` TEXT, "
                    + "`read_only_hint` INTEGER NOT NULL, "
                    + "`terminal_status` INTEGER, "
                    + "`created_at_ms` INTEGER NOT NULL, "
                    + "`started_at_ms` INTEGER, "
                    + "`terminal_at_ms` INTEGER, "
                    + "PRIMARY KEY(`conversation_task_id`))");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `uq_conversation_link_request` "
                    + "ON `conversation_task_link` (`runtime_request_id`)");
            db.execSQL("CREATE INDEX IF NOT EXISTS `idx_conversation_link_conversation` "
                    + "ON `conversation_task_link` (`conversation_id`)");
        }
    };

    /**
     * 加密数据库单例获取。
     *
     * <p>以下任一条件必须抛 IllegalStateException:
     * <ul>
     *   <li>keyProvider == null(装配错误,AppContainer 必须保证非空)</li>
     *   <li>keyProvider.getPassphrase() 返回 null 或 length == 0(KeyStore 取主密钥失败)</li>
     * </ul>
     * 调用方(AuditRuntimeGraph)捕获后退化 NoopAuditRepository。
     */
    public static synchronized MatrixDatabase getInstance(Context context,
            MasterKeyProvider keyProvider) {
        if (instance != null) return instance;
        if (keyProvider == null) {
            throw new IllegalStateException("MatrixDatabase init failed: keyProvider 为空");
        }
        char[] passphrase = keyProvider.getPassphrase();
        if (passphrase == null || passphrase.length == 0) {
            throw new IllegalStateException(
                    "MatrixDatabase init failed: passphrase 为空(Keystore 异常)");
        }
        try {
            // sqlcipher-android 不再由旧 SupportFactory 隐式装载 JNI；必须在创建
            // Room helper 前显式加载，避免首个数据库访问在 Binder/后台线程上失败。
            System.loadLibrary("sqlcipher");
            RoomDatabase.Builder<MatrixDatabase> builder = Room.databaseBuilder(
                    context.getApplicationContext(), MatrixDatabase.class, DB_NAME);
            // SQLCipher Android SupportOpenHelperFactory 接受 byte[] passphrase。
            // ISO-8859-1 char→byte 转换保证 32 字节随机值无损(每个 char 仅低 8 位有值)。
            byte[] passBytes = new byte[passphrase.length];
            for (int i = 0; i < passphrase.length; i++) {
                passBytes[i] = (byte) (passphrase[i] & 0xFF);
            }
            // char[] 转完 byte[] 后立即清零——MatrixDatabase 不再用 char[],
            // 减少密钥在堆内存驻留时间。byte[] 仍需保留(SupportFactory 持续使用)。
            Arrays.fill(passphrase, '\0');
            builder.openHelperFactory(new SupportOpenHelperFactory(passBytes));
            // 注册 v1→v2 Migration(audit_event userId)。
            // 加 v2→v3 Migration(audit_event requestEpoch)。
            // 加 v3→v4 Migration(新建 model_download 表)与 v4→v5 持久任务表。
            builder.addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                    MIGRATION_5_6, MIGRATION_6_7);
            // 显式 WAL——锁定并发读写语义,避免 OEM ROM 关闭 SQLite WAL。
            builder.setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING);
            instance = builder.build();
            Log.i(TAG, "[MatrixDatabase] init encrypted=true alias=" + keyProvider.alias()
                    + " version=7 entities=11 journalMode=WAL");
            return instance;
        } catch (Exception ex) {
            Log.e(TAG, "[MatrixDatabase] init FAILED cause="
                    + ex.getClass().getSimpleName() + ": " + ex.getMessage(), ex);
            throw new IllegalStateException("MatrixDatabase init failed", ex);
        }
    }

    /** 测试用——清空单例(仅 JVM 单测用,fake 注入路径)。 */
    static synchronized void resetForTest() {
        if (instance != null) {
            try {
                instance.close();
            } catch (Exception ignored) {
                // best effort
            }
            instance = null;
        }
    }
}
