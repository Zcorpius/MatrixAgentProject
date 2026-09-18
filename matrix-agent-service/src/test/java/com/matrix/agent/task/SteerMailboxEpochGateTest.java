package com.matrix.agent.task;
import com.matrix.agent.task.steer.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * SteerMailbox epoch gate + advanceEpoch 测试。
 *
 * <p>验证:
 * <ul>
 *   <li>advanceEpoch(newEpoch) 推进 currentEpoch;</li>
 *   <li>drain(sessionId, requestEpoch) 把 stamped.epoch &lt; requestEpoch 的 Steer drop;</li>
 *   <li>drop 的 Steer 通过 StaleSteerHandler.onStaleSteerDropped 回调(走 audit STEER_DROPPED_STALE);</li>
 *   <li>drain(sessionId) 旧 API(不传 epoch)仍全量返回——既有测试零回归;</li>
 *   <li>offer(sessionId, Steer) 默认用 currentEpoch 标记 stamped。</li>
 * </ul>
 */
public final class SteerMailboxEpochGateTest {

    @Test
    public void advanceEpochUpdatesCurrentEpoch() {
        SteerMailbox mailbox = new SteerMailbox();
        assertEquals(0L, mailbox.getCurrentEpoch());

        mailbox.advanceEpoch(5L);
        assertEquals(5L, mailbox.getCurrentEpoch());

        mailbox.advanceEpoch(10L);
        assertEquals(10L, mailbox.getCurrentEpoch());
    }

    @Test
    public void drainWithEpochDropsStaleSteer() {
        SteerMailbox mailbox = new SteerMailbox();
        RecordingHandler handler = new RecordingHandler();
        mailbox.setStaleHandler(handler);

        // Steer A: epoch=1 (stale), Steer B: epoch=3 (current)
        mailbox.offer("s1", Steer.reprompt("改下"), 1L);
        mailbox.offer("s1", Steer.reprompt("再改下"), 3L);
        mailbox.advanceEpoch(3L);

        List<Steer> retained = mailbox.drain("s1", 3L);

        assertEquals("仅 1 个 retained(epoch=3 的)", 1, retained.size());
        assertEquals("retained 是 epoch=3 的 Steer", "再改下", retained.get(0).getPayload());
        assertEquals("1 个 stale drop 被记录", 1, handler.dropCount.get());
        assertEquals("队列已清空", 0, mailbox.pendingCount("s1"));
    }

    @Test
    public void staleHandlerReceivesStampedEpochAndCurrentEpoch() {
        SteerMailbox mailbox = new SteerMailbox();
        RecordingHandler handler = new RecordingHandler();
        mailbox.setStaleHandler(handler);

        mailbox.offer("s1", Steer.forceTool("vehicle.climate", null), 1L);
        mailbox.advanceEpoch(5L);

        mailbox.drain("s1", 5L);

        assertEquals(1, handler.dropCount.get());
        assertEquals("stampedEpoch=1", 1L, handler.lastStampedEpoch);
        assertEquals("currentEpoch=5", 5L, handler.lastCurrentEpoch);
        assertEquals("sessionId 正确传递", "s1", handler.lastSessionId);
        assertEquals("Steer 类型正确传递", Steer.Type.FORCE_TOOL, handler.lastSteer.getType());
    }

    @Test
    public void drainWithEpochKeepsFreshSteer() {
        SteerMailbox mailbox = new SteerMailbox();
        RecordingHandler handler = new RecordingHandler();
        mailbox.setStaleHandler(handler);

        mailbox.offer("s1", Steer.reprompt("fresh"), 5L);
        mailbox.advanceEpoch(5L);

        List<Steer> retained = mailbox.drain("s1", 5L);

        assertEquals("epoch=5 与 requestEpoch=5 相等 → 不 stale,保留", 1, retained.size());
        assertEquals("无 drop", 0, handler.dropCount.get());
    }

    @Test
    public void drainLegacyWithoutEpochReturnsAll() {
        SteerMailbox mailbox = new SteerMailbox();
        RecordingHandler handler = new RecordingHandler();
        mailbox.setStaleHandler(handler);

        mailbox.offer("s1", Steer.reprompt("old"), 1L);
        mailbox.offer("s1", Steer.reprompt("new"), 3L);
        mailbox.advanceEpoch(3L);

        // 旧 drain(sessionId) 不传 epoch——全量返回,旧版兼容
        List<Steer> all = mailbox.drain("s1");

        assertEquals("旧 API 返回全部 2 条(包括 epoch=1 的 stale)", 2, all.size());
        assertEquals("旧 API 不触发 stale drop", 0, handler.dropCount.get());
    }

    @Test
    public void offerWithoutEpochUsesCurrentEpoch() {
        SteerMailbox mailbox = new SteerMailbox();
        mailbox.advanceEpoch(7L);

        // 旧 offer(sessionId, Steer) 默认用 currentEpoch=7L 标记
        mailbox.offer("s1", Steer.reprompt("fresh"));

        // drain 用 requestEpoch=7L——stamped.epoch=7 不 stale → 保留
        List<Steer> retained = mailbox.drain("s1", 7L);
        assertEquals("offer 默认 stamped=currentEpoch,不被 drop", 1, retained.size());
    }

    @Test
    public void staleHandlerFailureDoesNotPropagate() {
        SteerMailbox mailbox = new SteerMailbox();
        mailbox.setStaleHandler((sessionId, steer, stampedEpoch, currentEpoch) -> {
            throw new RuntimeException("simulated audit failure");
        });

        mailbox.offer("s1", Steer.reprompt("stale"), 1L);
        mailbox.advanceEpoch(2L);

        // stale handler 抛异常——SteerMailbox 必须 catch,不向上传播
        List<Steer> retained = mailbox.drain("s1", 2L);

        assertEquals("drop 仍生效,retained 为空", 0, retained.size());
    }

    @Test
    public void nullStaleHandlerLogsButDoesNotThrow() {
        SteerMailbox mailbox = new SteerMailbox();
        // 不注入 staleHandler——默认 null,仅 log warn

        mailbox.offer("s1", Steer.reprompt("stale"), 1L);
        mailbox.advanceEpoch(2L);

        List<Steer> retained = mailbox.drain("s1", 2L);
        assertEquals("无 handler 时 stale 仍 drop,retained 为空", 0, retained.size());
    }

    @Test
    public void clearUserDataSimulationAdvanceThenOfferThenDrain() {
        // 模拟 Repository.clearUserData 完整序列:
        // 1. Steer A 在 clearUserData 之前已 offer (stamped.epoch=0)
        // 2. clearUserData 推进 epoch → advanceEpoch(1L)
        // 3. clearUserData 同时 clearAll() 清空队列
        // 4. 用户在新 epoch 重新 offer Steer B (stamped.epoch=1)
        // 5. 新任务 drain("session", requestEpoch=1L) → 仅保留 B,A 已被 clearAll 清掉
        SteerMailbox mailbox = new SteerMailbox();
        RecordingHandler handler = new RecordingHandler();
        mailbox.setStaleHandler(handler);

        mailbox.offer("s1", Steer.reprompt("pre-clear"), 0L);
        mailbox.advanceEpoch(1L);
        mailbox.clearAll();
        mailbox.offer("s1", Steer.reprompt("post-clear"), 1L);

        List<Steer> retained = mailbox.drain("s1", 1L);

        assertEquals("clearAll 已清掉 pre-clear,retained 仅 post-clear", 1, retained.size());
        assertEquals("post-clear", retained.get(0).getPayload());
        assertEquals("clearAll 在 advanceEpoch 之后,pre-clear 不进 drop handler",
                0, handler.dropCount.get());
    }

    private static final class RecordingHandler implements StaleSteerHandler {
        final AtomicInteger dropCount = new AtomicInteger(0);
        String lastSessionId;
        Steer lastSteer;
        long lastStampedEpoch;
        long lastCurrentEpoch;

        @Override
        public void onStaleSteerDropped(String sessionId, Steer steer, long stampedEpoch,
                long currentEpoch) {
            dropCount.incrementAndGet();
            lastSessionId = sessionId;
            lastSteer = steer;
            lastStampedEpoch = stampedEpoch;
            lastCurrentEpoch = currentEpoch;
        }
    }
}
