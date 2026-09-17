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
 * <p>启动期由 MemoryRuntimeGraph 调用一次,迁移完成后清空 SP 文件
 * (避免双源失步)。迁移幂等——已迁移(SP 为空)直接跳过。
 *
 * <p><b>迁移策略</b>(原子 transaction + 条件 clear SP):
 * <ul>
 *   <li>SP key 格式 {@code userId + "." + key}——切分出 userId / prefKey,写入 Room
 *       memory_record(userId, zone="global", layer="preference", key=prefKey, value)。</li>
 *   <li>SP EPOCH_KEY(值 Long/Integer)→ Room memory_record(__system__, __system__, preference,
 *       __epoch__, long.toString(value))。RoomMemoryStore 构造时 queryByKey 加载。</li>
 *   <li>SP 历史 zone-less 偏好 zone 默认 "global"(与 MemoryScope.ofLegacy 一致)。</li>
 *   <li><b>全部 entities 收集后用同一次 Room transaction 写入</b>——任一 upsert 失败 transaction
 *       rollback,<b>SP 不清空</b>(下次启动重试);仅当 transaction 全部成功才 {@code spSource.clear()}。</li>
 * </ul>
 *
 * <p><b>失败场景</b>:Room transaction 抛异常(SQLCipher 不可用 / 磁盘满 / 单条 upsert 约束冲突)
 * → catch + log + <b>不清空 SP</b>,启动继续。RoomMemoryStore 仍构造(基于现有 Room 数据),
 * SP 留下次启动重试——迁移幂等。
 *
 * <p><b>测试性</b>:SP 读写通过 {@link SpSource} 抽象,JVM 测试注入 in-memory fake;
 * 生产环境通过 {@link #fromSharedPreferences} 工厂方法包装真实 {@link SharedPreferences}。
 */
public final class RoomMemoryMigrator {
    private static final String TAG = "MatrixAgent";
    public static final String SP_FILE_NAME = "matrix_agent_memory";
    public static final String SP_EPOCH_KEY = "__epoch__";

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

    /**
     * 执行 SP → Room 迁移——幂等,失败 best-effort 不抛,SP 保留供下次重试。
     *
     * <p>全部 entities 收集后用同一次 Room transaction 写入,任一失败 transaction
     * rollback + SP 不清空,杜绝"部分写入成功后 SP 被清空"的数据丢失窗口。
     */
    public void migrate() {
        Map<String, ?> all = spSource.getAll();
        if (all.isEmpty()) {
            Log.i(TAG, "[RoomMemoryMigrator] SP empty, skip");
            return;
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

        if (entities.isEmpty()) {
            // SP 仅含非法格式 entries —— 视为已迁移,清 SP 避免下次重复扫
            boolean cleared = spSource.clear();
            Log.i(TAG, "[RoomMemoryMigrator] no valid entities, spCleared=" + cleared);
            return;
        }

        try {
            if (transactionRunner != null) {
                transactionRunner.runInTransaction(() -> {
                    for (MemoryRecordEntity entity : entities) {
                        dao.upsert(entity);
                    }
                });
            } else {
                for (MemoryRecordEntity entity : entities) {
                    dao.upsert(entity);
                }
            }
        } catch (Exception ex) {
            Log.w(TAG, "[RoomMemoryMigrator] migrate transaction FAILED, SP retained for retry"
                    + " entities=" + entities.size() + " cause=" + ex.getMessage());
            return;
        }

        boolean cleared = spSource.clear();
        Log.i(TAG, "[RoomMemoryMigrator] migrated prefs=" + entities.size()
                + " epoch=" + spEpoch + " spCleared=" + cleared);
    }

    private MemoryRecordEntity buildPreferenceEntity(String userId, String key, String value) {
        MemoryRecordEntity entity = new MemoryRecordEntity();
        entity.userId = userId;
        entity.zone = RoomMemoryStore.DEFAULT_ZONE;
        entity.layer = RoomMemoryStore.PREFERENCE_LAYER;
        entity.key = key;
        entity.value = value;
        entity.capturedAtMs = System.currentTimeMillis();
        return entity;
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
