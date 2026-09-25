package com.matrix.agent.data.memory;

import android.util.Log;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

public class InMemoryMemoryStore implements MemoryStore {
    private static final String TAG = "MatrixAgent";
    private final Map<MemoryScope, Map<String, PreferenceRecord>> values = new LinkedHashMap<>();
    private long lastWriteTimestamp;
    /**
     * data epoch——{@link #bumpEpoch()} 自增,
     * {@link #putPreferenceChecked} 严格校验。InMemoryMemoryStore 不持久化,重启回到 epoch=0
     * (与 Repository 重建后二者同步,无失步风险——生产路径用 {@link RoomMemoryStore}
     * 跨进程持久化)。
     */
    private final AtomicLong epoch = new AtomicLong(0L);

    @Override
    public synchronized void putPreference(String userId, String key, String value) {
        throw new UnsupportedOperationException("preference writes require request epoch");
    }

    @Override
    public synchronized void putPreference(MemoryScope scope, String key, String value) {
        throw new UnsupportedOperationException("preference writes require request epoch");
    }

    @Override
    public synchronized String getPreference(String userId, String key) {
        return getPreference(MemoryScope.ofLegacy(userId), key);
    }

    @Override
    public synchronized String getPreference(MemoryScope scope, String key) {
        Map<String, PreferenceRecord> userValues = values.get(scope);
        PreferenceRecord record = userValues == null ? null : userValues.get(
                PreferenceReferences.resolve(scope, key, userValues.keySet()));
        return record == null ? null : record.value();
    }

    @Override
    public synchronized Map<String, String> getAllPreferences(String userId) {
        return getAllPreferences(MemoryScope.ofLegacy(userId));
    }

    @Override
    public synchronized Map<String, String> getAllPreferences(MemoryScope scope) {
        Map<String, PreferenceRecord> userValues = values.get(scope);
        if (userValues == null) {
            return Collections.emptyMap();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (PreferenceRecord record : userValues.values()) result.put(record.key(), record.value());
        return Collections.unmodifiableMap(result);
    }

    @Override
    public synchronized java.util.List<PreferenceRecord> getPreferenceRecords(MemoryScope scope) {
        Map<String, PreferenceRecord> scoped = values.get(scope);
        return scoped == null ? java.util.List.of() : java.util.List.copyOf(scoped.values());
    }

    @Override
    public synchronized void clear(String userId) {
        values.keySet().removeIf(scope -> scope.getUserId().equals(userId));
    }

    // ---- data epoch 强一致清除 ----

    @Override
    public long currentEpoch() {
        return epoch.get();
    }

    /**
     * bumpEpoch 加 synchronized——与 {@link #putPreferenceChecked} / {@link #clear}
     * 共用同一把锁,消除 check-then-act race(旧任务在 putPreferenceChecked 内 epoch 校验通过后,
     * bumpEpoch 自增 epoch + clear 清数据,旧任务 putPreference 仍会写入已清的 values)。
     */
    @Override
    public synchronized long bumpEpoch() {
        long newEpoch = epoch.incrementAndGet();
        Log.i(TAG, "[MemoryStore] bumpEpoch -> " + newEpoch);
        return newEpoch;
    }

    /**
     * 原子"清空两个用户数据 + epoch 自增"——单次 synchronized 内完成,杜绝 race。
     */
    @Override
    public synchronized long clearUserDataAndBump(String userId1, String userId2) {
        return clearUsersAndBump(java.util.List.of(userId1, userId2));
    }

    @Override
    public synchronized long clearUsersAndBump(java.util.List<String> userIds) {
        long newEpoch = epoch.incrementAndGet();
        for (String userId : userIds) clear(userId);
        Log.i(TAG, "[MemoryStore] clearUserDataAndBump -> epoch=" + newEpoch
                + " clearedUsers=" + userIds.size());
        return newEpoch;
    }

    /**
     * 带 epoch 校验的 putPreference。
     *
     * <p>若 {@code requestEpoch != currentEpoch()},说明调用方在 clearUserData 之前启动,
     * 此时写入会把刚清的数据写回——拒绝,返回 false。
     *
     * <p>删除"epoch=0 兼容路径"——评审指出 0L 静默放行会让安全语义被绕过
     * (任何调用方传 0 都接受)。Repository 必须读 {@link #currentEpoch()} 显式注入当前值。
     */
    @Override
    public synchronized boolean putPreferenceChecked(String userId, String key, String value,
            long requestEpoch) {
        return putPreferenceChecked(MemoryScope.ofLegacy(userId), key, value, requestEpoch);
    }

    @Override
    public synchronized boolean putPreferenceChecked(MemoryScope scope, String key, String value,
            long requestEpoch) {
        return putPreferenceDetailed(scope, key, value, requestEpoch) == MemoryWriteOutcome.SAVED;
    }

    @Override
    public synchronized MemoryWriteOutcome putPreferenceDetailed(MemoryScope scope, String key,
            String value, long requestEpoch) {
        if (scope == null || RoomMemoryStore.SYSTEM_USER.equals(scope.getUserId())) return MemoryWriteOutcome.INVALID_REQUEST;
        if (!MemoryKeyCatalog.isPreferenceKey(key)) return MemoryWriteOutcome.INVALID_KEY;
        if (value == null || value.isBlank() || value.length() > 2048) return MemoryWriteOutcome.INVALID_VALUE;
        if (requestEpoch != epoch.get()) return MemoryWriteOutcome.STALE_EPOCH;
        Map<String, PreferenceRecord> scoped = values.computeIfAbsent(scope, ignored -> new LinkedHashMap<>());
        if (!scoped.containsKey(key) && scoped.size() >= RoomMemoryStore.MAX_EXPLICIT_RECORDS_PER_SCOPE) {
            return MemoryWriteOutcome.CAPACITY_REACHED;
        }
        lastWriteTimestamp = Math.max(System.currentTimeMillis(), lastWriteTimestamp + 1);
        scoped.put(key, new PreferenceRecord(key, value, lastWriteTimestamp, true));
        return MemoryWriteOutcome.SAVED;
    }

    @Override
    public synchronized boolean deletePreferenceChecked(MemoryScope scope, String key, long epochValue) {
        return deletePreferenceDetailed(scope, key, epochValue) == MemoryDeleteOutcome.DELETED;
    }

    @Override
    public synchronized MemoryDeleteOutcome deletePreferenceDetailed(MemoryScope scope,
            String key, long epochValue) {
        if (scope == null) return MemoryDeleteOutcome.INVALID_REQUEST;
        if (!MemoryKeyCatalog.isReadablePreferenceKey(key)) return MemoryDeleteOutcome.INVALID_KEY;
        if (epochValue != epoch.get()) return MemoryDeleteOutcome.STALE_EPOCH;
        Map<String, PreferenceRecord> scoped = values.get(scope);
        return scoped != null && scoped.remove(PreferenceReferences.resolve(scope, key, scoped.keySet())) != null
                ? MemoryDeleteOutcome.DELETED : MemoryDeleteOutcome.NOT_FOUND;
    }
}
