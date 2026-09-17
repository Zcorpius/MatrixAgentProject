package com.matrix.agent.voice;

import org.junit.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link VoiceEntryCoordinator} JVM 测试:§6.2/§6.3 闸门矩阵——
 * 来源校验 / 乱序丢弃 / 去重(窗口内拒绝,窗口外放行)/ 冷却 / 单会话仲裁 / LRU 淘汰。
 * 时间域全部基于事件自带 elapsedRealtime(纯事件流,直接驱动)。
 */
public final class VoiceEntryCoordinatorTest {

    private static final Set<String> ALLOW = Set.of(VoiceEntryCoordinator.SOURCE_SYSTEM_VIS);

    /** 记录派发 + 可控会话活跃态的假目标。 */
    private static final class FakeTarget implements VoiceEntryCoordinator.Target {
        final List<WakeEvent> dispatched = new CopyOnWriteArrayList<>();
        volatile boolean sessionActive;
        @Override public void onWakeAccepted(WakeEvent event) { dispatched.add(event); }
        @Override public boolean isSessionActive() { return sessionActive; }
    }

    private static WakeEvent event(long elapsed, String eventId) {
        return new WakeEvent(VoiceEntryCoordinator.SOURCE_SYSTEM_VIS, eventId, elapsed, null);
    }

    @Test
    public void validEvent_dispatched() {
        FakeTarget t = new FakeTarget();
        VoiceEntryCoordinator c = new VoiceEntryCoordinator(t, ALLOW);
        assertTrue(c.onWakeEvent(event(1000L, "e1")));
        assertEquals(1, t.dispatched.size());
        assertEquals(1, c.acceptedCount());
    }

    @Test
    public void disallowedSource_dropped() {
        FakeTarget t = new FakeTarget();
        VoiceEntryCoordinator c = new VoiceEntryCoordinator(t, ALLOW);
        assertFalse("非 allowlist 来源拒绝(§6.3-1)",
                c.onWakeEvent(new WakeEvent("RANDOM_BROADCAST", "e1", 1000L, null)));
        assertFalse("null 事件同样按非法来源计", c.onWakeEvent(null));
        assertEquals(2, c.droppedDisallowedSourceCount());
        assertTrue(t.dispatched.isEmpty());
    }

    @Test
    public void duplicateEventId_withinWindow_dropped_beyondWindow_accepted() {
        FakeTarget t = new FakeTarget();
        VoiceEntryCoordinator c = new VoiceEntryCoordinator(t, ALLOW, 0L, 10_000L, 64);
        assertTrue(c.onWakeEvent(event(1000L, "e1")));
        assertFalse("窗口内同 eventId+source 重复拒绝(§6.3-2)", c.onWakeEvent(event(1500L, "e1")));
        assertEquals(1, c.droppedDuplicateCount());
        assertTrue("超过去重窗口的重复 eventId 放行(系统侧新会话)",
                c.onWakeEvent(event(1000L + 10_001L, "e1")));
        assertEquals(2, c.acceptedCount());
    }

    @Test
    public void staleEvent_beyondWindow_dropped() {
        FakeTarget t = new FakeTarget();
        VoiceEntryCoordinator c = new VoiceEntryCoordinator(t, ALLOW, 0L, 10_000L, 64); // 关冷却,单测乱序闸门
        assertTrue(c.onWakeEvent(event(20_000L, "e1")));
        assertFalse("落后最新事件超过窗口的乱序投递拒绝(时钟回绕/迟到)",
                c.onWakeEvent(event(5_000L, "e2")));
        assertEquals(1, c.droppedStaleCount());
        assertTrue("窗口内的乱序(非重复)不按 stale 拒,走后续闸门",
                c.onWakeEvent(event(15_000L, "e3"))); // 20s-10s=10s ≤ 15s
        assertEquals(1, c.droppedStaleCount());
    }

    @Test
    public void cooldown_betweenAcceptedEvents_enforced() {
        FakeTarget t = new FakeTarget();
        VoiceEntryCoordinator c = new VoiceEntryCoordinator(t, ALLOW, 800L, 10_000L, 64);
        assertTrue(c.onWakeEvent(event(1000L, "e1")));
        assertFalse("冷却期内不同 eventId 也拒绝(系统重放/抖动)", c.onWakeEvent(event(1500L, "e2")));
        assertEquals(1, c.droppedCooldownCount());
        assertTrue(c.onWakeEvent(event(1800L, "e3"))); // 恰好 ≥800ms
        assertEquals(2, t.dispatched.size());
    }

    @Test
    public void activeSession_wakeIgnored_counted() {
        FakeTarget t = new FakeTarget();
        VoiceEntryCoordinator c = new VoiceEntryCoordinator(t, ALLOW, 0L, 10_000L, 64);
        assertTrue(c.onWakeEvent(event(1000L, "e1")));
        t.sessionActive = true; // LISTENING/THINKING/SPEAKING 中
        assertFalse("活跃会话期间重复 wake 忽略(§6.3-3 首版策略)",
                c.onWakeEvent(event(5000L, "e2")));
        assertEquals(1, c.droppedActiveSessionCount());
        t.sessionActive = false;
        assertTrue(c.onWakeEvent(event(6000L, "e3")));
    }

    @Test
    public void dedupLru_eviction_allowsRedispatchOfEvictedKey() {
        FakeTarget t = new FakeTarget();
        VoiceEntryCoordinator c = new VoiceEntryCoordinator(t, ALLOW, 0L, 10_000_000L, 2);
        assertTrue(c.onWakeEvent(event(1000L, "e1")));
        assertTrue(c.onWakeEvent(event(1100L, "e2")));
        assertTrue(c.onWakeEvent(event(1200L, "e3"))); // 容量 2,e1 被淘汰
        assertTrue("被淘汰的 key 不再命中去重(容量有界,不无限增长)",
                c.onWakeEvent(event(1300L, "e1")));
        assertEquals(4, t.dispatched.size());
    }

    @Test
    public void wakeEvent_validation_rejectsInvalid() {
        assertThrows(() -> new WakeEvent(null, "e1", 1L, null));
        assertThrows(() -> new WakeEvent("S", "", 1L, null));
        assertThrows(() -> new WakeEvent("S", "e1", -1L, null));
    }

    private static void assertThrows(Runnable r) {
        try {
            r.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("应抛 IllegalArgumentException");
    }
}
