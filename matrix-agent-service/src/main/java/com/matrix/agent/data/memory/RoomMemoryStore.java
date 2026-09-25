package com.matrix.agent.data.memory;

import android.util.Log;

import com.matrix.agent.data.db.MemoryRecordDao;
import com.matrix.agent.data.db.MatrixDatabase;
import com.matrix.agent.data.db.SessionHistoryDao;
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
 * 把 preference 数据搬到 memory_record 表(layer="preference"),与 SQLCipher 加密及记忆两表与 epoch 的原子清除协同。
 *
 * <p><b>设计决策</b>:
 * <ul>
 *   <li>epoch 持久化到特殊行 ({@code __system__}/__system__/preference/__epoch__),与
 *       preference 行同表同层,跨进程重启不丢——与 SP EPOCH_KEY 语义对齐。</li>
 *   <li>历史 user-only 读取明确映射到 {@code global};直接写入一律拒绝，
 *       新写入必须携带请求 epoch 与 {@link MemoryScope}。</li>
 *   <li>{@code synchronized(lock)} 和数据库事务共同保护 epoch 校验及写入。
 *       跨 Store 实例时仍以事务内重读的数据库值为准。</li>
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
    public static final int MAX_EXPLICIT_RECORDS_PER_SCOPE = 1024;

    /** 函数式事务包装器——生产传 {@code database::runInTransaction},测试传 {@code Runnable::run}。 */
    @FunctionalInterface
    public interface TransactionRunner {
        void runInTransaction(Runnable body);
    }

    private final MemoryRecordDao dao;
    private final SessionHistoryDao sessionHistoryDao;
    private final TransactionRunner transactionRunner;
    private final AtomicLong epoch;
    private final CountDownLatch epochLoaded = new CountDownLatch(1);
    private final Object lock = new Object();
    private volatile RuntimeException initializationFailure;

    /** Production composition: both persistent memory tables share the epoch reset transaction. */
    public RoomMemoryStore(MatrixDatabase database, Executor executor) {
        this(database.memoryRecordDao(), database.sessionHistoryDao(), database::runInTransaction, executor);
    }

    /** Compatibility constructor for read-only callers; mutations requiring atomicity fail closed. */
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
        this(dao, null, transactionRunner, databaseExecutor);
    }

    private RoomMemoryStore(MemoryRecordDao dao,
            SessionHistoryDao sessionHistoryDao,
            TransactionRunner transactionRunner, Executor databaseExecutor) {
        this.dao = dao;
        this.sessionHistoryDao = sessionHistoryDao;
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
            initializationFailure = new IllegalStateException("epoch initialization rejected", rejected);
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
        } catch (RuntimeException failure) {
            initializationFailure = failure;
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
        if (initializationFailure != null) throw initializationFailure;
    }

    private static long loadEpochFromRow(MemoryRecordDao dao) {
        try {
            MemoryRecordEntity row = dao.queryByKey(
                    SYSTEM_USER, SYSTEM_ZONE, PREFERENCE_LAYER, EPOCH_KEY);
            if (row == null) return 0L;
            if (row.value == null) throw new IllegalStateException("null epoch value");
            long loaded = Long.parseLong(row.value);
            if (loaded < 0) throw new IllegalStateException("negative epoch value");
            return loaded;
        } catch (Exception ex) {
            throw new IllegalStateException("RoomMemoryStore epoch read failed", ex);
        }
    }

    @Override
    public void putPreference(String userId, String key, String value) {
        throw new UnsupportedOperationException("preference writes require request epoch");
    }

    @Override
    public void putPreference(MemoryScope scope, String key, String value) {
        throw new UnsupportedOperationException("preference writes require request epoch");
    }

    private MemoryWriteOutcome upsertPreferenceLocked(MemoryScope scope, String key, String value) {
        if (dao.queryByKey(scope.getUserId(), scope.getZone().wireValue(),
                PREFERENCE_LAYER, key) == null
                && dao.countByUserZoneLayer(scope.getUserId(), scope.getZone().wireValue(),
                        PREFERENCE_LAYER) >= MAX_EXPLICIT_RECORDS_PER_SCOPE) {
            return MemoryWriteOutcome.CAPACITY_REACHED;
        }
        MemoryRecordEntity entity = new MemoryRecordEntity();
        entity.userId = scope.getUserId();
        entity.zone = scope.getZone().wireValue();
        entity.layer = PREFERENCE_LAYER;
        entity.key = key;
        entity.value = value;
        entity.capturedAtMs = System.currentTimeMillis();
        entity.sourceSessionId = "explicit";
        dao.upsert(entity);
        return MemoryWriteOutcome.SAVED;
    }

    @Override
    public String getPreference(String userId, String key) {
        return getPreference(MemoryScope.ofLegacy(userId), key);
    }

    @Override
    public String getPreference(MemoryScope scope, String key) {
        awaitEpochLoaded();
        String resolved = resolvePreferenceReference(scope, key);
        if (resolved == null) return null;
        MemoryRecordEntity row = dao.queryByKey(scope.getUserId(), scope.getZone().wireValue(),
                PREFERENCE_LAYER, resolved);
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
            result.put(row.key, row.value);
        }
        return Collections.unmodifiableMap(result);
    }

    @Override
    public List<String> getPreferenceKeys(MemoryScope scope) {
        awaitEpochLoaded();
        return List.copyOf(dao.queryKeysByUserZoneLayer(scope.getUserId(),
                scope.getZone().wireValue(), PREFERENCE_LAYER));
    }

    @Override
    public List<PreferenceRecord> getPreferenceRecords(MemoryScope scope) {
        awaitEpochLoaded();
        List<MemoryRecordEntity> rows = dao.queryByUserZoneLayer(scope.getUserId(),
                scope.getZone().wireValue(), PREFERENCE_LAYER);
        List<PreferenceRecord> records = new java.util.ArrayList<>(rows.size());
        for (MemoryRecordEntity row : rows) {
            if (row.key != null && row.value != null) {
                records.add(new PreferenceRecord(row.key, row.value, row.capturedAtMs,
                        "explicit".equals(row.sourceSessionId)));
            }
        }
        return Collections.unmodifiableList(records);
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
        // Request construction may run on the UI thread. Persistent mutations always re-read
        // the authoritative row inside their transaction, so this cache is only a request hint.
        return epoch.get();
    }

    @Override
    public long bumpEpoch() {
        awaitEpochLoaded();
        synchronized (lock) {
            final long[] next = {0L};
            try {
                requireTransactionRunner().runInTransaction(() -> {
                    next[0] = Math.addExact(loadEpochFromRow(dao), 1L);
                    upsertEpochRowLocked(next[0]);
                });
                epoch.set(next[0]);
                return next[0];
            } catch (Exception ex) {
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
    public boolean putPreferenceChecked(MemoryScope scope, String key, String value, long requestEpoch) {
        return putPreferenceDetailed(scope, key, value, requestEpoch) == MemoryWriteOutcome.SAVED;
    }

    @Override
    public MemoryWriteOutcome putPreferenceDetailed(MemoryScope scope, String key, String value,
            long requestEpoch) {
        if (scope == null || SYSTEM_USER.equals(scope.getUserId())) return MemoryWriteOutcome.INVALID_REQUEST;
        if (!MemoryKeyCatalog.isPreferenceKey(key)) return MemoryWriteOutcome.INVALID_KEY;
        if (value == null || value.isBlank() || value.length() > 2048) return MemoryWriteOutcome.INVALID_VALUE;
        try {
            awaitEpochLoaded();
        } catch (RuntimeException failure) {
            return MemoryWriteOutcome.STORAGE_FAILURE;
        }
        synchronized (lock) {
            try {
                MemoryWriteOutcome[] outcome = {MemoryWriteOutcome.STORAGE_FAILURE};
                requireTransactionRunner().runInTransaction(() -> {
                    long current = loadEpochFromRow(dao);
                    epoch.set(current);
                    outcome[0] = requestEpoch == current ? upsertPreferenceLocked(scope, key, value)
                            : MemoryWriteOutcome.STALE_EPOCH;
                });
                return outcome[0];
            } catch (RuntimeException failure) {
                Log.w(TAG, "[RoomMemoryStore] preference write failed cause="
                        + failure.getClass().getSimpleName());
                return MemoryWriteOutcome.STORAGE_FAILURE;
            }
        }
    }

    @Override
    public boolean deletePreferenceChecked(MemoryScope scope, String key, long requestEpoch) {
        return deletePreferenceDetailed(scope, key, requestEpoch) == MemoryDeleteOutcome.DELETED;
    }

    @Override
    public MemoryDeleteOutcome deletePreferenceDetailed(MemoryScope scope, String key,
            long requestEpoch) {
        if (scope == null || SYSTEM_USER.equals(scope.getUserId())) return MemoryDeleteOutcome.INVALID_REQUEST;
        if (!MemoryKeyCatalog.isReadablePreferenceKey(key)) return MemoryDeleteOutcome.INVALID_KEY;
        try { awaitEpochLoaded(); }
        catch (RuntimeException failure) { return MemoryDeleteOutcome.STORAGE_FAILURE; }
        synchronized (lock) {
            try {
                MemoryDeleteOutcome[] outcome = {MemoryDeleteOutcome.STORAGE_FAILURE};
                requireTransactionRunner().runInTransaction(() -> {
                    long current = loadEpochFromRow(dao);
                    epoch.set(current);
                    if (requestEpoch != current) {
                        outcome[0] = MemoryDeleteOutcome.STALE_EPOCH;
                        return;
                    }
                    String resolved = resolvePreferenceReference(scope, key);
                    outcome[0] = resolved != null && dao.deleteByKey(scope.getUserId(),
                            scope.getZone().wireValue(), PREFERENCE_LAYER, resolved) > 0
                            ? MemoryDeleteOutcome.DELETED : MemoryDeleteOutcome.NOT_FOUND;
                });
                return outcome[0];
            } catch (Exception failure) {
                Log.w(TAG, "[RoomMemoryStore] preference delete failed cause="
                        + failure.getClass().getSimpleName());
                return MemoryDeleteOutcome.STORAGE_FAILURE;
            }
        }
    }

    @Override
    public long clearUserDataAndBump(String userId1, String userId2) {
        return clearUsersAndBump(java.util.List.of(userId1, userId2));
    }

    @Override
    public long clearUsersAndBump(java.util.List<String> userIds) {
        awaitEpochLoaded();
        if (userIds == null || userIds.isEmpty()
                || userIds.contains(SYSTEM_USER) || userIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("invalid reset owners");
        }
        synchronized (lock) {
            final long[] next = {0L};
            try {
                Runnable body = () -> {
                    next[0] = Math.addExact(loadEpochFromRow(dao), 1L);
                    upsertEpochRowLocked(next[0]);
                    dao.upsert(RoomMemoryMigrator.markerEntity());
                    for (String userId : userIds) {
                        dao.deleteByUser(userId);
                        if (sessionHistoryDao != null) sessionHistoryDao.deleteByUser(userId);
                    }
                };
                requireTransactionRunner().runInTransaction(body);
                epoch.set(next[0]);
                return next[0];
            } catch (Exception ex) {
                throw new IllegalStateException(
                        "RoomMemoryStore.clearUserDataAndBump transaction failed", ex);
            }
        }
    }

    private String resolvePreferenceReference(MemoryScope scope, String reference) {
        if (!PreferenceReferences.isAlias(reference)) return reference;
        return PreferenceReferences.resolve(scope, reference, dao.queryKeysByUserZoneLayer(
                scope.getUserId(), scope.getZone().wireValue(), PREFERENCE_LAYER));
    }

    private TransactionRunner requireTransactionRunner() {
        if (transactionRunner == null) {
            throw new IllegalStateException("transaction runner required for persistent mutation");
        }
        return transactionRunner;
    }
}
