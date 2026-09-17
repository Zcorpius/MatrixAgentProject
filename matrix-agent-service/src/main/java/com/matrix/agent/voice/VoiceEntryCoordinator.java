package com.matrix.agent.voice;

import com.matrix.agent.voice.port.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 唤醒入口协调器(§6.2/§6.3,纯 Java,JVM 可测):外部 WakeEvent 的统一闸门——
 * 来源校验 → 乱序丢弃 → 去重 → 冷却 → 单会话仲裁,全部通过才派发到 {@link Target}。
 *
 * <p>职责对应 §6.3:2(去重,防同一 wake 多会话)、3(首版单活跃会话,重复 wake 忽略)、
 * 8(固定 Actor.DRIVER/默认音区——由 Controller 会话路径保证,本类不据 audioZoneId 路由)。
 * §6.3-1(签名权限/系统绑定来源保证)在 Adapter 层,本类只认 allowlist 枚举。
 *
 * <p><b>时间域</b>:去重/乱序/冷却全部基于事件自带的 {@code elapsedRealtime}
 * (SystemClock.elapsedRealtime 单调时钟)判定,不引入第二时钟——纯事件流函数,JVM 直接驱动。
 *
 * <p><b>参数</b>(构造注入,默认常量;不进 {@code VoicePolicyConfig}——12 参全字段构造
 * 已满,为单字段 churn 全部调用点不值,阶段 3 后如需产品化调参再并入):
 * cooldownMs 相邻接受事件最小间隔;dedupWindowMs 同一 eventId+source 的重复判定窗口
 * (兼作乱序落后容忍窗);dedupCapacity LRU 容量。
 *
 * <p><b>线程安全</b>:事件来自系统 binder 线程,方法 synchronized;内部 LRU(访问序)
 * 淘汰最旧 key,插入时顺带清理过窗条目。计数器供测试断言与后续 2F 指标。
 */
public final class VoiceEntryCoordinator {

    /** 首版唤醒来源 allowlist(§6.3-1:OEM 签名/系统绑定回调来源在 Adapter 层校验)。 */
    public static final String SOURCE_SYSTEM_VIS = "SYSTEM_VIS";

    /** 接受后派发目标(Runtime 经 lambda 适配;isSessionActive 用于单会话仲裁)。 */
    public interface Target {
        /** 接受的唤醒事件(固定 DRIVER/默认音区语义由 Runtime→Controller 会话路径保证)。 */
        void onWakeAccepted(WakeEvent event);

        /** @return true=当前存在活跃语音会话(LISTENING/THINKING/CONFIRMING/SPEAKING)。 */
        boolean isSessionActive();
    }

    public static final long DEFAULT_COOLDOWN_MS = 800L;
    public static final long DEFAULT_DEDUP_WINDOW_MS = 10_000L;
    public static final int DEFAULT_DEDUP_CAPACITY = 64;

    private final Target target;
    private final Set<String> allowedSources;
    private final long cooldownMs;
    private final long dedupWindowMs;
    private final int dedupCapacity;
    /** source|eventId → 该事件 elapsedRealtime;访问序 LRU。 */
    private final LinkedHashMap<String, Long> recentEvents = new LinkedHashMap<>(16, 0.75f, true);
    private long maxSeenElapsed = Long.MIN_VALUE;
    private long lastAcceptedElapsed = Long.MIN_VALUE;

    private int accepted;
    private int droppedDisallowedSource;
    private int droppedDuplicate;
    private int droppedStale;
    private int droppedCooldown;
    private int droppedActiveSession;

    public VoiceEntryCoordinator(Target target, Set<String> allowedSources) {
        this(target, allowedSources, DEFAULT_COOLDOWN_MS, DEFAULT_DEDUP_WINDOW_MS, DEFAULT_DEDUP_CAPACITY);
    }

    public VoiceEntryCoordinator(Target target, Set<String> allowedSources,
            long cooldownMs, long dedupWindowMs, int dedupCapacity) {
        if (target == null) throw new IllegalArgumentException("target 不能为空");
        if (allowedSources == null || allowedSources.isEmpty()) throw new IllegalArgumentException("allowedSources 不能为空");
        if (cooldownMs < 0) throw new IllegalArgumentException("cooldownMs 不能为负");
        if (dedupWindowMs < 0) throw new IllegalArgumentException("dedupWindowMs 不能为负");
        if (dedupCapacity < 1) throw new IllegalArgumentException("dedupCapacity 不能小于 1");
        this.target = target;
        this.allowedSources = Set.copyOf(allowedSources);
        this.cooldownMs = cooldownMs;
        this.dedupWindowMs = dedupWindowMs;
        this.dedupCapacity = dedupCapacity;
    }

    /** 入口:校验并按需派发。返回 true=已接受并派发。 */
    public synchronized boolean onWakeEvent(WakeEvent event) {
        if (event == null || !allowedSources.contains(event.source())) {
            droppedDisallowedSource++;
            return false;
        }
        long elapsed = event.elapsedRealtime();
        // 乱序:落后已见最新事件超过去重窗口 → 丢弃(时钟回绕/迟到投递)
        if (maxSeenElapsed != Long.MIN_VALUE && elapsed < maxSeenElapsed - dedupWindowMs) {
            droppedStale++;
            return false;
        }
        maxSeenElapsed = Math.max(maxSeenElapsed, elapsed);
        // 去重:同 eventId+source 在窗口内重复 → 丢弃(防同一 wake 多会话,§6.3-2)
        String key = event.source() + "|" + event.eventId();
        Long seen = recentEvents.get(key);
        if (seen != null && elapsed - seen <= dedupWindowMs) {
            droppedDuplicate++;
            return false;
        }
        // 冷却:距上次接受的绝对间隔不足 → 丢弃(不同 eventId 的系统重放/抖动;
        // 乱序事件 elapsed 可能早于上次接受,负差不构成"间隔够久")
        if (lastAcceptedElapsed != Long.MIN_VALUE && Math.abs(elapsed - lastAcceptedElapsed) < cooldownMs) {
            droppedCooldown++;
            return false;
        }
        // 单会话仲裁:活跃会话期间重复 wake 忽略(§6.3-3 首版策略)
        if (target.isSessionActive()) {
            droppedActiveSession++;
            return false;
        }
        recentEvents.put(key, elapsed);
        while (recentEvents.size() > dedupCapacity) { // LRU 容量淘汰
            recentEvents.remove(recentEvents.entrySet().iterator().next().getKey());
        }
        long newest = elapsed; // 过窗清理(调用方持锁)
        recentEvents.values().removeIf(t -> newest - t > dedupWindowMs);
        lastAcceptedElapsed = elapsed;
        accepted++;
        target.onWakeAccepted(event);
        return true;
    }

    // ---- 计数(测试断言 + 后续 2F 指标接入点) ----

    public synchronized int acceptedCount() { return accepted; }
    public synchronized int droppedDisallowedSourceCount() { return droppedDisallowedSource; }
    public synchronized int droppedDuplicateCount() { return droppedDuplicate; }
    public synchronized int droppedStaleCount() { return droppedStale; }
    public synchronized int droppedCooldownCount() { return droppedCooldown; }
    public synchronized int droppedActiveSessionCount() { return droppedActiveSession; }
}
