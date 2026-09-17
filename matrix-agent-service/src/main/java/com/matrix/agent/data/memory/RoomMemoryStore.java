package com.matrix.agent.data.memory;

import android.util.Log;

import com.matrix.agent.data.memory.MemoryStore;
import com.matrix.agent.data.db.MemoryRecordDao;
import com.matrix.agent.data.db.MemoryRecordEntity;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Room-backed MemoryStore 主路径实现。
 *
 * <p>替换历史明文偏好二元组存储，
 * 把 preference 数据搬到 memory_record 表(layer="preference"),与 SQLCipher 加密 + 4 表原子
 * clearUserData 协同。
 *
 * <p><b>设计决策</b>:
 * <ul>
 *   <li>epoch 持久化到特殊行 ({@code __system__}/__system__/preference/__epoch__),与
 *       preference 行同表同层,跨进程重启不丢——与 SP EPOCH_KEY 语义对齐。</li>
 *   <li>历史 user-only 调用明确映射到 {@code global};新调用必须传 {@link MemoryScope},
 *       以 {@code (userId, zone)} 为读写边界。</li>
 *   <li>{@code synchronized(lock)} 保护"epoch 校验 + 写数据"复合操作,避免 check-then-act
 *       race 修复。</li>
 *   <li>{@link #clearUserDataAndBump(String, String)} 走 {@link TransactionRunner}(生产装配
 *       {@code database::runInTransaction})——bumpEpoch + delete×2 在同一 Room transaction
 *       原子完成,失败回滚 epoch + 抛异常反馈上层。</li>
 * </ul>
 *
 * <p><b>failure boundary</b>:本类不在装配期吞 Room 异常；组合根捕获后只能进入显式的
 * volatile-memory degraded mode，绝不悄悄回退到明文 SharedPreferences。
 */
public final class RoomMemoryStore implements MemoryStore {
    private static final String TAG = "MatrixAgent";

    public static final String SYSTEM_USER = "__system__";
    public static final String SYSTEM_ZONE = "__system__";
    public static final String PREFERENCE_LAYER = "preference";
    public static final String DEFAULT_ZONE = "global";
    public static final String EPOCH_KEY = "__epoch__";

    /** 函数式事务包装器——生产传 {@code database::runInTransaction},测试传 {@code Runnable::run}。 */
    @FunctionalInterface
    public interface TransactionRunner {
        void runInTransaction(Runnable body);
    }

    private final MemoryRecordDao dao;
    private final TransactionRunner transactionRunner;
    private final AtomicLong epoch;
    private final CountDownLatch epochLoaded = new CountDownLatch(1);
    private final Object lock = new Object();

    /** 单参构造器——无事务包装器(clearUserDataAndBump 退化为 best-effort 顺序执行)。 */
    public RoomMemoryStore(MemoryRecordDao dao) {
        this(dao, null, null);
    }

    public RoomMemoryStore(MemoryRecordDao dao, TransactionRunner transactionRunner) {
        this(dao, transactionRunner, null);
    }

    /**
     * Production constructor: initializes the persisted epoch on the Host DB executor instead
     * of the Android main thread.  Every public memory operation waits for this one-shot load,
     * so a cold start cannot overwrite a persisted epoch with a provisional zero.
     */
    public RoomMemoryStore(MemoryRecordDao dao, TransactionRunner transactionRunner,
            Executor databaseExecutor) {
        this.dao = dao;
        this.transactionRunner = transactionRunner;
        this.epoch = new AtomicLong(0L);
        if (databaseExecutor == null) {
            initializeEpoch();
            return;
        }
        try {
            databaseExecutor.execute(this::initializeEpoch);
        } catch (RuntimeException rejected) {
            // Do not leave callers blocked if the bounded executor has already shut down.
            Log.e(TAG, "[RoomMemoryStore] epoch initialization rejected", rejected);
            epochLoaded.countDown();
        }
    }

    private void initializeEpoch() {
        try {
            long loaded = loadEpochFromRow(dao);
            synchronized (lock) {
                epoch.set(loaded);
            }
            Log.i(TAG, "[RoomMemoryStore] init loadedEpoch=" + loaded
                    + " txRunner=" + (transactionRunner != null ? "present" : "absent"));
        } finally {
            epochLoaded.countDown();
        }
    }

    private void awaitEpochLoaded() {
        try {
            if (!epochLoaded.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("RoomMemoryStore epoch initialization timed out");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("RoomMemoryStore epoch initialization interrupted",
                    interrupted);
        }
    }

    private static long loadEpochFromRow(MemoryRecordDao dao) {
        try {
            MemoryRecordEntity row = dao.queryByKey(
                    SYSTEM_USER, SYSTEM_ZONE, PREFERENCE_LAYER, EPOCH_KEY);
            if (row == null || row.value == null) return 0L;
            return Long.parseLong(row.value);
        } catch (Exception ex) {
            Log.w(TAG, "[RoomMemoryStore] loadEpoch FAILED, default 0L cause="
                    + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return 0L;
        }
    }

    @Override
    public void putPreference(String userId, String key, String value) {
        putPreference(MemoryScope.ofLegacy(userId), key, value);
    }

    @Override
    public void putPreference(MemoryScope scope, String key, String value) {
        awaitEpochLoaded();
        synchronized (lock) {
            upsertPreferenceLocked(scope, key, value);
        }
    }

    private void upsertPreferenceLocked(MemoryScope scope, String key, String value) {
        MemoryRecordEntity entity = new MemoryRecordEntity();
        entity.userId = scope.getUserId();
        entity.zone = scope.getZone().wireValue();
        entity.layer = PREFERENCE_LAYER;
        entity.key = key;
        entity.value = value;
        entity.capturedAtMs = System.currentTimeMillis();
        dao.upsert(entity);
    }

    @Override
    public String getPreference(String userId, String key) {
        return getPreference(MemoryScope.ofLegacy(userId), key);
    }

    @Override
    public String getPreference(MemoryScope scope, String key) {
        awaitEpochLoaded();
        MemoryRecordEntity row = dao.queryByKey(scope.getUserId(), scope.getZone().wireValue(),
                PREFERENCE_LAYER, key);
        return row == null ? null : row.value;
    }

    @Override
    public Map<String, String> getAllPreferences(String userId) {
        return getAllPreferences(MemoryScope.ofLegacy(userId));
    }

    @Override
    public Map<String, String> getAllPreferences(MemoryScope scope) {
        awaitEpochLoaded();
        List<MemoryRecordEntity> rows = dao.queryByUserZoneLayer(scope.getUserId(),
                scope.getZone().wireValue(), PREFERENCE_LAYER);
        Map<String, String> result = new LinkedHashMap<>();
        for (MemoryRecordEntity row : rows) {
            if (EPOCH_KEY.equals(row.key)) continue;  // 永不暴露 epoch 行给 caller
            result.put(row.key, row.value);
        }
        return Collections.unmodifiableMap(result);
    }

    @Override
    public void clear(String userId) {
        awaitEpochLoaded();
        synchronized (lock) {
            dao.deleteByUser(userId);
        }
    }

    @Override
    public long currentEpoch() {
        awaitEpochLoaded();
        return epoch.get();
    }

    @Override
    public long bumpEpoch() {
        awaitEpochLoaded();
        synchronized (lock) {
            long newEpoch = epoch.incrementAndGet();
            try {
                upsertEpochRowLocked(newEpoch);
                Log.i(TAG, "[RoomMemoryStore] bumpEpoch -> " + newEpoch + " (persisted)");
                return newEpoch;
            } catch (Exception ex) {
                epoch.decrementAndGet();
                Log.e(TAG, "[RoomMemoryStore] bumpEpoch FAILED, rolled back to " + epoch.get()
                        + " cause=" + ex.getClass().getSimpleName() + ": " + ex.getMessage());
                throw new IllegalStateException("RoomMemoryStore.bumpEpoch persist failed", ex);
            }
        }
    }

    private void upsertEpochRowLocked(long newEpoch) {
        MemoryRecordEntity entity = new MemoryRecordEntity();
        entity.userId = SYSTEM_USER;
        entity.zone = SYSTEM_ZONE;
        entity.layer = PREFERENCE_LAYER;
        entity.key = EPOCH_KEY;
        entity.value = Long.toString(newEpoch);
        entity.capturedAtMs = System.currentTimeMillis();
        dao.upsert(entity);
    }

    @Override
    public boolean putPreferenceChecked(String userId, String key, String value, long requestEpoch) {
        return putPreferenceChecked(MemoryScope.ofLegacy(userId), key, value, requestEpoch);
    }

    @Override
    public boolean putPreferenceChecked(MemoryScope scope, String key, String value,
            long requestEpoch) {
        awaitEpochLoaded();
        synchronized (lock) {
            long current = epoch.get();
            if (requestEpoch != current) {
                Log.w(TAG, "[RoomMemoryStore] reject stale write scope=" + scope
                        + " key=" + key
                        + " requestEpoch=" + requestEpoch
                        + " currentEpoch=" + current
                        + " — clearUserData 已发生,陈旧写入被拒绝");
                return false;
            }
            try {
                upsertPreferenceLocked(scope, key, value);
                return true;
            } catch (Exception ex) {
                Log.e(TAG, "[RoomMemoryStore] putPreferenceChecked FAILED scope=" + scope
                        + " key=" + key + " cause=" + ex.getClass().getSimpleName()
                        + ": " + ex.getMessage());
                return false;
            }
        }
    }

    @Override
    public long clearUserDataAndBump(String userId1, String userId2) {
        awaitEpochLoaded();
        synchronized (lock) {
            final long newEpoch = epoch.incrementAndGet();
            try {
                Runnable body = () -> {
                    upsertEpochRowLocked(newEpoch);
                    dao.deleteByUser(userId1);
                    dao.deleteByUser(userId2);
                };
                if (transactionRunner != null) {
                    transactionRunner.runInTransaction(body);
                } else {
                    body.run();
                }
                Log.i(TAG, "[RoomMemoryStore] clearUserDataAndBump -> epoch=" + newEpoch
                        + (transactionRunner != null ? " (atomic transaction)" : " (best-effort)"));
                return newEpoch;
            } catch (Exception ex) {
                epoch.decrementAndGet();
                Log.e(TAG, "[RoomMemoryStore] clearUserDataAndBump FAILED, epoch rolled back to "
                        + epoch.get() + " cause=" + ex.getClass().getSimpleName()
                        + ": " + ex.getMessage());
                throw new IllegalStateException(
                        "RoomMemoryStore.clearUserDataAndBump transaction failed", ex);
            }
        }
    }
}
