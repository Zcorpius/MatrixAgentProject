package com.matrix.agent.data.memory;

import com.matrix.agent.identity.AgentRequest;

import java.util.Map;
import java.util.List;
import java.util.ArrayList;

public interface MemoryStore {
    /** Legacy fixture API. Persistent implementations reject writes without request epoch. */
    default void putPreference(MemoryScope scope, String key, String value) {
        throw new UnsupportedOperationException("scoped preference write not implemented");
    }

    /** Legacy fixture API. Production callers use {@link #putPreferenceChecked}. */
    void putPreference(String userId, String key, String value);

    /** Reads a preference from one explicit occupant scope. */
    default String getPreference(MemoryScope scope, String key) {
        throw new UnsupportedOperationException("scoped preference read not implemented");
    }

    String getPreference(String userId, String key);

    /** Lists only preferences owned by one explicit occupant scope. */
    default Map<String, String> getAllPreferences(MemoryScope scope) {
        throw new UnsupportedOperationException("scoped preference list not implemented");
    }

    Map<String, String> getAllPreferences(String userId);

    /** Complete scoped directory, including legacy entries excluded from recall. */
    default List<String> getPreferenceKeys(MemoryScope scope) {
        return List.copyOf(getAllPreferences(scope).keySet());
    }

    /** Ordered retrieval metadata; legacy stores conservatively classify entries as imported. */
    default List<PreferenceRecord> getPreferenceRecords(MemoryScope scope) {
        List<PreferenceRecord> records = new ArrayList<>();
        for (Map.Entry<String, String> entry : getAllPreferences(scope).entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                records.add(new PreferenceRecord(entry.getKey(), entry.getValue(), 0L, false));
            }
        }
        return records;
    }

    void clear(String userId);

    // data epoch 强一致清除支持
    // MemoryStore 是 epoch 单一权威 (持久化跨进程重启)

    /**
     * 返回 MemoryStore 当前的 data epoch。
     *
     * <p>{@link com.matrix.agent.task.AgentRuntimeRepository} 不再维护独立的 epochCounter,
     * 直接读本方法作为 AgentRequest.epoch 注入。生产 {@link RoomMemoryStore} 从 SQLCipher
     * 读取已持久化 epoch，跨进程重启不丢失，避免 Repository 与存储的版本失步。
     *
     * <p>没有 epoch 实现的 Store 必须拒绝调用，不能把不可用误判为初始 epoch 0。
     */
    default long currentEpoch() {
        throw new UnsupportedOperationException("epoch not implemented");
    }

    /**
     * 自增 epoch 并返回新值,使所有旧 epoch 的写入失效。
     *
     * <p>没有原子 epoch 实现的 Store 必须拒绝调用。
     * Repository.clearUserData 是唯一调用方,调用后所有旧 epoch 的 putPreferenceChecked
     * 必须返回 {@code false} 并不修改存储。
     *
     * @return bump 后的新 epoch,用于日志 / 测试断言
     */
    default long bumpEpoch() {
        throw new UnsupportedOperationException("epoch bump not implemented");
    }

    /**
     * 带 epoch 校验的 putPreference。
     *
     * <p>默认实现拒绝写入；具体 Store 必须显式实现 epoch 门。
     *
     * <p>{@link InMemoryMemoryStore} / {@link RoomMemoryStore} 覆盖本方法,
     * 当 {@code epoch != currentEpoch()} 时记录 warn 并返回 {@code false},不修改存储。
     *
     * <p>不再保留"epoch=0 兼容路径"——评审指出该兼容路径会让安全语义
     * 被静默绕过 (任何调用方传 0 都接受)。Repository.execute 必须读 memoryStore.currentEpoch()
     * 显式注入当前值,而非依赖 0L 兜底。
     *
     * <p>本方法在 {@link InMemoryMemoryStore} / {@link RoomMemoryStore}
     * 中必须与 {@link #bumpEpoch()} / {@link #clear(String)} / {@link #clearUserDataAndBump} 用同一把锁,
     * 避免 check-then-act race(旧任务校验通过后,clearUserData 切 epoch + 清数据,旧任务 putPreference
     * 在 SP 队列后落盘 → 偏好"复活")。
     *
     * @return true 表示写入成功；false 兼容旧调用方，具体原因由 putPreferenceDetailed 返回
     */
    default boolean putPreferenceChecked(String userId, String key, String value, long epoch) {
        return false;
    }

    /** Epoch-checked scoped write.  Legacy implementations retain GLOBAL semantics. */
    default boolean putPreferenceChecked(MemoryScope scope, String key, String value, long epoch) {
        return false;
    }

    /** Structured outcome for production callers. Legacy adapters report unknown failure honestly. */
    default MemoryWriteOutcome putPreferenceDetailed(MemoryScope scope, String key, String value,
            long epoch) {
        return putPreferenceChecked(scope, key, value, epoch)
                ? MemoryWriteOutcome.SAVED : MemoryWriteOutcome.STORAGE_FAILURE;
    }

    /** Delete one scoped preference only while the request epoch is current. */
    default boolean deletePreferenceChecked(MemoryScope scope, String key, long epoch) {
        return deletePreferenceDetailed(scope, key, epoch) == MemoryDeleteOutcome.DELETED;
    }

    default MemoryDeleteOutcome deletePreferenceDetailed(MemoryScope scope, String key, long epoch) {
        return MemoryDeleteOutcome.STORAGE_FAILURE;
    }

    /**
     * 原子"清空两个用户数据 + epoch 自增"——评审指出 clearUserData(bumpEpoch + clear×2)
     * 三步分离有 check-then-act race。本方法把三者收敛为原子操作。
     *
     * <p>默认实现拒绝清除；具体 Store 必须提供原子操作。
     * 生产 {@link InMemoryMemoryStore} / {@link RoomMemoryStore} 覆盖为原子版本:
     * <ul>
     *   <li>InMemoryMemoryStore: {@code synchronized} 单锁保护 epoch 自增 + clear×2;</li>
     *   <li>RoomMemoryStore: {@code synchronized} 单锁 + Room transaction，同步落盘 epoch +
     *       clear×2；事务失败回滚 epoch 并向上层报告。</li>
     * </ul>
     *
     * @return bump 后的新 epoch
     */
    default long clearUserDataAndBump(String userId1, String userId2) {
        throw new UnsupportedOperationException("atomic user data clear not implemented");
    }

    default long clearUsersAndBump(java.util.List<String> userIds) {
        throw new UnsupportedOperationException("atomic multi-user clear not implemented");
    }
}
