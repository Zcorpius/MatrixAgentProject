package com.matrix.agent.data.memory;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.matrix.agent.data.db.MemoryRecordDao;
import com.matrix.agent.data.db.MemoryRecordEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SharedPreferences 历史 preference → Room memory_record 一次性迁移。
 *
 * <p>启动期由 MemoryRuntimeGraph 调用。Room 中的完成标记是一次性导入边界；
 * 标记存在时即使旧 SP 清除失败也绝不重放数据，并且持久化装配会显式降级直到残留 SP 可清除。
 *
 * <p><b>迁移策略</b>(原子 transaction + 条件 clear SP):
 * <ul>
 *   <li>SP key 格式 {@code userId + "." + key}。可确认的 demo-driver/passenger
 *       分别进入 driver/passenger；其他历史 owner 保持 global 域。</li>
 *   <li>SP EPOCH_KEY(值 Long/Integer)→ Room memory_record(__system__, __system__, preference,
 *       __epoch__, long.toString(value))。RoomMemoryStore 构造时 queryByKey 加载。</li>
 *   <li><b>全部 entities 收集后用同一次 Room transaction 写入</b>——任一 upsert 失败 transaction
 *       rollback,<b>SP 不清空</b>(下次启动重试);仅当 transaction 全部成功才 {@code spSource.clear()}。</li>
 * </ul>
 *
 * <p><b>失败场景</b>:事务或 SP 清除失败时返回 false；装配层不会暴露持久化记忆源。
 *
 * <p><b>测试性</b>:SP 读写通过 {@link SpSource} 抽象,JVM 测试注入 in-memory fake;
 * 生产环境通过 {@link #fromSharedPreferences} 工厂方法包装真实 {@link SharedPreferences}。
 */
public final class RoomMemoryMigrator {
    private static final String TAG = "MatrixAgent";
    public static final String SP_FILE_NAME = "matrix_agent_memory";
    public static final String SP_EPOCH_KEY = "__epoch__";
    public static final String MIGRATION_MARKER_KEY = "__legacy_migration_complete__";

    /** SP 数据源抽象——让 Migrator 在 JVM 测试里可注入 in-memory fake。 */
    public interface SpSource {
        Map<String, ?> getAll();
        boolean clear();
    }

    private final SpSource spSource;
    private final MemoryRecordDao dao;
    private final RoomMemoryStore.TransactionRunner transactionRunner;

    public RoomMemoryMigrator(SpSource spSource, MemoryRecordDao dao,
            RoomMemoryStore.TransactionRunner transactionRunner) {
        this.spSource = spSource;
        this.dao = dao;
        this.transactionRunner = transactionRunner;
    }

    /** 生产工厂:从 Context 拿 SharedPreferences,包装成 SpSource。 */
    public static RoomMemoryMigrator fromSharedPreferences(Context context, MemoryRecordDao dao,
            RoomMemoryStore.TransactionRunner transactionRunner) {
        final SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(SP_FILE_NAME, Context.MODE_PRIVATE);
        return new RoomMemoryMigrator(new SpSource() {
            @Override
            public Map<String, ?> getAll() {
                return prefs.getAll();
            }

            @Override
            public boolean clear() {
                return prefs.edit().clear().commit();
            }
        }, dao, transactionRunner);
    }

    public static boolean clearLegacySharedPreferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(SP_FILE_NAME,
                Context.MODE_PRIVATE).edit().clear().commit();
    }

    /**
     * 执行 SP → Room 迁移。返回 false 表示残留来源尚未安全处理，调用方必须降级。
     *
     * <p>全部 entities 收集后用同一次 Room transaction 写入,任一失败 transaction
     * rollback + SP 不清空,杜绝"部分写入成功后 SP 被清空"的数据丢失窗口。
     */
    public boolean migrate() {
        Map<String, ?> all = spSource.getAll();
        if (migrationCompleted()) {
            if (!all.isEmpty() && !spSource.clear()) {
                Log.w(TAG, "[RoomMemoryMigrator] legacy source could not be removed; import remains disabled");
                return false;
            }
            return true;
        }
        if (all.isEmpty()) {
            markComplete();
            return true;
        }

        long spEpoch = 0L;
        List<MemoryRecordEntity> entities = new ArrayList<>();
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            String spKey = entry.getKey();
            if (SP_EPOCH_KEY.equals(spKey)) {
                Object v = entry.getValue();
                if (v instanceof Long) {
                    spEpoch = (Long) v;
                } else if (v instanceof Integer) {
                    spEpoch = ((Integer) v).longValue();
                }
                continue;
            }
            if (!(entry.getValue() instanceof String)) continue;
            int dot = spKey.indexOf('.');
            if (dot <= 0 || dot >= spKey.length() - 1) continue;  // 不符合 userId.key 格式
            String userId = spKey.substring(0, dot);
            String prefKey = spKey.substring(dot + 1);
            String value = (String) entry.getValue();
            entities.add(buildPreferenceEntity(userId, prefKey, value));
        }
        if (spEpoch > 0L) {
            entities.add(buildEpochEntity(spEpoch));
        }

        try {
            if (transactionRunner == null) throw new IllegalStateException("migration requires transaction");
            transactionRunner.runInTransaction(() -> {
                if (migrationCompleted()) return;
                for (MemoryRecordEntity entity : entities) {
                    MemoryRecordEntity existing = dao.queryByKey(entity.userId, entity.zone,
                            entity.layer, entity.key);
                    if (existing == null) {
                        dao.upsert(entity);
                    } else if (RoomMemoryStore.EPOCH_KEY.equals(entity.key)) {
                        long oldEpoch = Long.parseLong(entity.value);
                        long currentEpoch = Long.parseLong(existing.value);
                        if (oldEpoch > currentEpoch) dao.upsert(entity);
                    }
                }
                dao.upsert(markerEntity());
            });
        } catch (Exception ex) {
            Log.w(TAG, "[RoomMemoryMigrator] migrate transaction FAILED, SP retained for retry"
                    + " entities=" + entities.size() + " cause=" + ex.getMessage());
            return false;
        }

        boolean cleared = spSource.clear();
        Log.i(TAG, "[RoomMemoryMigrator] migrated prefs=" + entities.size()
                + " epoch=" + spEpoch + " spCleared=" + cleared);
        return cleared;
    }

    private MemoryRecordEntity buildPreferenceEntity(String userId, String key, String value) {
        MemoryRecordEntity entity = new MemoryRecordEntity();
        entity.userId = userId;
        entity.zone = "demo-driver".equals(userId) ? "driver"
                : "demo-passenger".equals(userId) ? "passenger" : RoomMemoryStore.DEFAULT_ZONE;
        entity.layer = RoomMemoryStore.PREFERENCE_LAYER;
        entity.key = key;
        entity.value = value;
        entity.capturedAtMs = System.currentTimeMillis();
        return entity;
    }

    private boolean migrationCompleted() {
        return dao.queryByKey(RoomMemoryStore.SYSTEM_USER, RoomMemoryStore.SYSTEM_ZONE,
                RoomMemoryStore.PREFERENCE_LAYER, MIGRATION_MARKER_KEY) != null;
    }

    private void markComplete() {
        if (transactionRunner == null) throw new IllegalStateException("migration requires transaction");
        transactionRunner.runInTransaction(() -> dao.upsert(markerEntity()));
    }

    public static MemoryRecordEntity markerEntity() {
        MemoryRecordEntity marker = new MemoryRecordEntity();
        marker.userId = RoomMemoryStore.SYSTEM_USER;
        marker.zone = RoomMemoryStore.SYSTEM_ZONE;
        marker.layer = RoomMemoryStore.PREFERENCE_LAYER;
        marker.key = MIGRATION_MARKER_KEY;
        marker.value = "1";
        marker.capturedAtMs = System.currentTimeMillis();
        return marker;
    }

    private MemoryRecordEntity buildEpochEntity(long epochValue) {
        MemoryRecordEntity entity = new MemoryRecordEntity();
        entity.userId = RoomMemoryStore.SYSTEM_USER;
        entity.zone = RoomMemoryStore.SYSTEM_ZONE;
        entity.layer = RoomMemoryStore.PREFERENCE_LAYER;
        entity.key = RoomMemoryStore.EPOCH_KEY;
        entity.value = Long.toString(epochValue);
        entity.capturedAtMs = System.currentTimeMillis();
        return entity;
    }
}
