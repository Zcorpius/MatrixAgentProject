package com.matrix.agent.task;
import com.matrix.agent.task.steer.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * SteerMailbox.peekDeferred 回归测试。
 *
 * <p>旧的 hasDeferredSteer 调 drain() 把整个队列掏空,导致无 DEFER 时
 * REPROMPT/FORCE_TOOL 也被永久丢弃。peekDeferred 只看不动,保留非 DEFER 给下一轮 drain。
 */
public final class SteerMailboxPeekTest {

    @Test
    public void emptyQueueReturnsFalse() {
        SteerMailbox mailbox = new SteerMailbox();
        assertFalse(mailbox.peekDeferred("session-empty"));
    }

    @Test
    public void peekDeferredReturnsTrueWhenDeferPresent() {
        SteerMailbox mailbox = new SteerMailbox();
        mailbox.offer("s1", Steer.reprompt("临时改下"));
        mailbox.offer("s1", Steer.defer());

        assertTrue("队列含 DEFER → peekDeferred=true", mailbox.peekDeferred("s1"));
        assertEquals("peek 不动队列,pendingCount 仍是 2", 2, mailbox.pendingCount("s1"));
    }

    @Test
    public void peekDeferredReturnsFalseWhenOnlyRepromptAndForceTool() {
        SteerMailbox mailbox = new SteerMailbox();
        mailbox.offer("s1", Steer.reprompt("请把温度设到 24"));
        Map<String, Object> args = new HashMap<>();
        args.put("zone", "driver");
        args.put("temperature", 24);
        mailbox.offer("s1", Steer.forceTool("vehicle.climate.set_temperature", args));

        assertFalse("队列只有 REPROMPT/FORCE_TOOL → peekDeferred=false",
                mailbox.peekDeferred("s1"));
        assertEquals("pendingCount 仍是 2", 2, mailbox.pendingCount("s1"));
    }

    @Test
    public void afterPeekDeferredDrainStillReturnsAllSteers() {
        SteerMailbox mailbox = new SteerMailbox();
        mailbox.offer("s1", Steer.reprompt("改下"));
        mailbox.offer("s1", Steer.defer());

        boolean deferred = mailbox.peekDeferred("s1");
        assertTrue(deferred);

        List<Steer> drained = mailbox.drain("s1");
        assertEquals("peek 后 drain 仍能取到全部 2 条 Steer", 2, drained.size());
        assertEquals(Steer.Type.REPROMPT, drained.get(0).getType());
        assertEquals(Steer.Type.DEFER, drained.get(1).getType());
    }

    @Test
    public void differentSessionsPeekIndependently() {
        SteerMailbox mailbox = new SteerMailbox();
        mailbox.offer("s1", Steer.defer());
        mailbox.offer("s2", Steer.reprompt("不打扰"));

        assertTrue(mailbox.peekDeferred("s1"));
        assertFalse(mailbox.peekDeferred("s2"));
    }

    @Test
    public void nullSessionReturnsFalse() {
        SteerMailbox mailbox = new SteerMailbox();
        assertFalse(mailbox.peekDeferred(null));
    }

    /** steerId 去重（评估 v1.0 §4.3 第三层幂等）：同 id 重复 offer 只消费一次；空 id 不去重。 */
    @Test
    public void duplicateSteerIdIsOfferedOnlyOnce() {
        SteerMailbox mailbox = new SteerMailbox();
        mailbox.offer("s1", Steer.reprompt("第一次", "steer-msg-1"));
        mailbox.offer("s1", Steer.reprompt("安全重试（同 id）", "steer-msg-1"));
        mailbox.offer("s1", Steer.reprompt("第二条", "steer-msg-2"));
        // 空 steerId（旧路径）不去重
        mailbox.offer("s1", Steer.reprompt("无 id A"));
        mailbox.offer("s1", Steer.reprompt("无 id B"));

        java.util.List<com.matrix.agent.task.steer.Steer> drained = mailbox.drain("s1");
        org.junit.Assert.assertEquals("去重后恰好 4 条（1+1+无id×2）", 4, drained.size());
        org.junit.Assert.assertEquals("第一次", drained.get(0).getPayload());
        org.junit.Assert.assertEquals("第二条", drained.get(1).getPayload());
        // 会话隔离：另一 session 的同 id 不受影响
        mailbox.offer("s2", Steer.reprompt("另一会话", "steer-msg-1"));
        org.junit.Assert.assertEquals(1, mailbox.drain("s2").size());
    }
}
