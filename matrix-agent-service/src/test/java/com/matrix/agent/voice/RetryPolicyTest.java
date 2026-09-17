package com.matrix.agent.voice;

import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link RetryPolicy} 回归测试(P2-2)。注入 fake clock/sleeper,覆盖成功接管 / 持续忙超时 / 取消 /
 * 真实失败 4 路径(守护 LockBusy 60s 退避逻辑)。
 */
public final class RetryPolicyTest {

    private static final class FakeClock implements RetryPolicy.Clock {
        final AtomicLong t = new AtomicLong();
        @Override public long nanoTime() { return t.get(); }
        void advance(long ns) { t.addAndGet(ns); }
    }

    @Test
    public void retry_succeeds_whenBodyPasses() throws Exception {
        FakeClock clock = new FakeClock();
        RetryPolicy.Sleeper sleeper = millis -> { };
        AtomicInteger attempts = new AtomicInteger();
        boolean ok = RetryPolicy.retry(clock, sleeper, Long.MAX_VALUE, new long[]{100, 200},
                () -> false,
                () -> attempts.incrementAndGet());
        assertTrue("body 成功(不抛)应返回 true", ok);
        assertEquals("成功应只尝试 1 次", 1, attempts.get());
    }

    @Test
    public void retry_timeout_whenBodyAlwaysBusy() throws Exception {
        FakeClock clock = new FakeClock();
        // sleep 推进 clock(模拟时间流逝),让退避累积超 deadline
        RetryPolicy.Sleeper sleeper = millis -> clock.advance(millis * 1_000_000L);
        AtomicInteger attempts = new AtomicInteger();
        boolean ok = RetryPolicy.retry(clock, sleeper, 5_000_000_000L, new long[]{500, 1000, 2000},
                () -> false,
                () -> { attempts.incrementAndGet(); throw new RetryPolicy.RetryableFailure(); });
        assertFalse("持续忙(RetryableFailure)应超时 false", ok);
        assertTrue("应多次退避尝试(>1):" + attempts.get(), attempts.get() > 1);
    }

    @Test
    public void retry_cancelled_whenShouldStop() throws Exception {
        FakeClock clock = new FakeClock();
        RetryPolicy.Sleeper sleeper = millis -> { };
        AtomicInteger attempts = new AtomicInteger();
        boolean ok = RetryPolicy.retry(clock, sleeper, Long.MAX_VALUE, new long[]{100},
                () -> attempts.get() >= 2, // 第 2 次尝试后取消
                () -> { attempts.incrementAndGet(); throw new RetryPolicy.RetryableFailure(); });
        assertFalse("取消(shouldStop)应 false", ok);
        assertTrue("应尝试 >=2 次后取消:" + attempts.get(), attempts.get() >= 2);
    }

    @Test
    public void retry_propagatesRealFailure() throws Exception {
        FakeClock clock = new FakeClock();
        RetryPolicy.Sleeper sleeper = millis -> { };
        AtomicInteger attempts = new AtomicInteger();
        try {
            RetryPolicy.retry(clock, sleeper, Long.MAX_VALUE, new long[]{100},
                    () -> false,
                    () -> { attempts.incrementAndGet(); throw new IOException("REAL_FAIL"); });
            fail("真实 IOException 应向上传播,不重试");
        } catch (IOException e) {
            assertTrue("应含 REAL_FAIL: " + e.getMessage(), e.getMessage().contains("REAL_FAIL"));
        }
        assertEquals("真实失败不重试,只尝试 1 次", 1, attempts.get());
    }

    @Test
    public void retry_doesNotRunBody_afterDeadline() throws Exception {
        // P2-1: sleep 截断为剩余时间 + sleep 后复查 deadline,body 不应执行(防越界返回 true)
        FakeClock clock = new FakeClock();
        RetryPolicy.Sleeper sleeper = millis -> clock.advance(millis * 1_000_000L);
        AtomicInteger attempts = new AtomicInteger();
        // deadline=50ms, planned sleep=100ms → 截断为 50ms → sleep 后 clock=50ms=deadline → 退出,body 不跑
        boolean ok = RetryPolicy.retry(clock, sleeper, 50_000_000L, new long[]{100},
                () -> false,
                () -> { attempts.incrementAndGet(); throw new RetryPolicy.RetryableFailure(); });
        assertFalse("sleep 后过 deadline 应 false", ok);
        assertEquals("sleep 后过 deadline,body 不应执行", 0, attempts.get());
    }
}
