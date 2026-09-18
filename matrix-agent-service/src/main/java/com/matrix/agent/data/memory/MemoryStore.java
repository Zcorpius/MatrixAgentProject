package com.matrix.agent.data.memory;

import com.matrix.agent.identity.AgentRequest;

import java.util.Map;

public interface MemoryStore {
    /**
     * Stores a preference in an explicit occupant scope.
     *
     * <p>This is the canonical API.  The user-only overloads below are retained solely for
     * callers that predate multi-zone memory and always map to {@link MemoryScope#ofLegacy}.
     */
    default void putPreference(MemoryScope scope, String key, String value) {
        putPreference(scope.getUserId(), key, value);
    }

    void putPreference(String userId, String key, String value);

    /** Reads a preference from one explicit occupant scope. */
    default String getPreference(MemoryScope scope, String key) {
        return getPreference(scope.getUserId(), key);
    }

    String getPreference(String userId, String key);

    /** Lists only preferences owned by one explicit occupant scope. */
    default Map<String, String> getAllPreferences(MemoryScope scope) {
        return getAllPreferences(scope.getUserId());
    }

    Map<String, String> getAllPreferences(String userId);

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
     * <p>默认 {@code 0L}——旧实现 / 测试 fake 不参与 epoch 校验,行为不变。
     */
    default long currentEpoch() { return 0L; }

    /**
     * 自增 epoch 并返回新值,使所有旧 epoch 的写入失效。
     *
     * <p>默认返回 {@code 0L}(空操作)——旧实现 / 测试 fake 不参与。
     * Repository.clearUserData 是唯一调用方,调用后所有旧 epoch 的 putPreferenceChecked
     * 必须返回 {@code false} 并不修改存储。
     *
     * @return bump 后的新 epoch,用于日志 / 测试断言
     */
    default long bumpEpoch() { return 0L; }

    /**
     * 带 epoch 校验的 putPreference。
     *
     * <p>默认实现忽略 epoch 直接调 {@link #putPreference(String, String, String)}——
     * 旧实现 / 测试 fake 不参与 epoch 校验,行为不变。
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
     * @return true 表示写入成功,false 表示因 epoch 陈旧被拒绝
     */
    default boolean putPreferenceChecked(String userId, String key, String value, long epoch) {
        putPreference(userId, key, value);
        return true;
    }

    /** Epoch-checked scoped write.  Legacy implementations retain GLOBAL semantics. */
    default boolean putPreferenceChecked(MemoryScope scope, String key, String value, long epoch) {
        return putPreferenceChecked(scope.getUserId(), key, value, epoch);
    }

    /**
     * 原子"清空两个用户数据 + epoch 自增"——评审指出 clearUserData(bumpEpoch + clear×2)
     * 三步分离有 check-then-act race。本方法把三者收敛为原子操作。
     *
     * <p>默认实现非原子(顺序 bumpEpoch + clear + clear),旧实现 / 测试 fake 用。
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
        long newEpoch = bumpEpoch();
        clear(userId1);
        clear(userId2);
        return newEpoch;
    }
}
