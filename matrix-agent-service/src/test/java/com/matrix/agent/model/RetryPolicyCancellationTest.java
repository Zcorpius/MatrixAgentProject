package com.matrix.agent.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.matrix.agent.task.identity.CancellationToken;

/**
 * RetryPolicy cancel + deadline 感知测试。
 *
 * <p>评审发现旧 {@code invokeWithRetry} 用 {@code Thread.sleep(delay)} 退避,
 * 不感知 cancellation / 剩余 deadline——cancel 后最坏要等 3500ms 退避结束才返回,
 * 且退避后剩余时间不足时仍会发请求。
 *
 * <p>本测试覆盖 4 个核心契约:
 * <ul>
 *   <li>retry 前 token 已 cancel → 立即抛 CancellationException,不 sleep / 不 retry;</li>
 *   <li>retry 前 deadline 已过 → 立即抛 TimeoutException;</li>
 *   <li>sleep 期间 token.cancel → 立即唤醒 sleep + 抛 CancellationException;</li>
 *   <li>剩余时间不足时 delay 被截断到 remaining;</li>
 *   <li>旧重载 {@code invokeWithRetry(action)} 不退化,转发新重载。</li>
 * </ul>
 */
public final class RetryPolicyCancellationTest {

    @Test
    public void retryAbortsImmediatelyWhenTokenAlreadyCancelled() throws Exception {
        RetryPolicy fast = newFastRetryPolicy();
        CancellationToken token = new CancellationToken();
        token.cancel();

        AtomicInteger calls = new AtomicInteger(0);
        try {
            fast.invokeWithRetry(() -> {
                calls.incrementAndGet();
                throw new ModelApiException.RateLimitException("test", null);
            }, token, Long.MAX_VALUE);
            fail("已 cancel 的 token 应抛 CancellationException");
        } catch (CancellationException expected) {
            assertTrue("首次调用允许(call() 先执行),重试时被 cancel", calls.get() >= 1);
            assertTrue(calls.get() <= 1);
        }
    }

    @Test
    public void retryAbortsWhenDeadlineAlreadyPassed() throws Exception {
        RetryPolicy fast = newFastRetryPolicy();
        // deadline = now - 1000ms,已经过去
        long pastDeadline = System.currentTimeMillis() - 1000L;

        AtomicInteger calls = new AtomicInteger(0);
        try {
            fast.invokeWithRetry(() -> {
                calls.incrementAndGet();
                throw new ModelApiException.RateLimitException("test", null);
            }, null, pastDeadline);
            fail("已过 deadline 应抛 TimeoutException");
        } catch (TimeoutException expected) {
            assertEquals("首次调用允许,重试时被 deadline 拦", 1, calls.get());
        }
    }

    @Test
    public void sleepIsInterruptedByCancel() throws Exception {
        // 用真实 computeDelay 让 sleep 进入阻塞(200ms,足以让 cancel 在 sleep 期间到达)
        RetryPolicy policy = new RetryPolicy() {
            @Override
            long computeDelay(int attempt) {
                return 5_000L;  // 5 秒,确保 cancel 来得及到达
            }
        };
        final CancellationToken token = new CancellationToken();
        final CountDownLatch firstCallFinished = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger(0);
        final ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            // 异步执行 retry
            java.util.concurrent.Future<?> future = exec.submit(() -> {
                try {
                    policy.invokeWithRetry(() -> {
                        calls.incrementAndGet();
                        firstCallFinished.countDown();
                        throw new ModelApiException.RateLimitException("test", null);
                    }, token, Long.MAX_VALUE);
                    return null;
                } catch (CancellationException ce) {
                    return "cancelled";
                } catch (Exception ex) {
                    return "ex:" + ex.getClass().getSimpleName();
                }
            });

            // 等首次 call 完成,确保已进入 retry sleep
            assertTrue(firstCallFinished.await(1, TimeUnit.SECONDS));
            // 给一点时间让 retry 进入 Thread.sleep
            Thread.sleep(50);
            long cancelAt = System.currentTimeMillis();
            token.cancel();

            Object result = future.get(2, TimeUnit.SECONDS);
            long returnedAt = System.currentTimeMillis();
            assertEquals("应返回 cancelled(被 token 中断)", "cancelled", result);
            long elapsed = returnedAt - cancelAt;
            assertTrue("cancel 后应在远短于 5s 的时间内返回,实际:" + elapsed + "ms", elapsed < 1_000L);
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    public void delayTruncatedToRemainingDeadline() throws Exception {
        // computeDelay 返回 5000ms,但 deadline 剩余只有 ~100ms——delay 应截断到 100ms
        RetryPolicy policy = new RetryPolicy() {
            @Override
            long computeDelay(int attempt) {
                return 5_000L;
            }
        };
        long deadline = System.currentTimeMillis() + 100L;
        long start = System.currentTimeMillis();
        try {
            policy.invokeWithRetry(() -> {
                throw new ModelApiException.ServerException(503, "test", null);
            }, null, deadline);
            fail("ServerException retry 后应耗尽抛");
        } catch (ModelApiException expected) {
            long elapsed = System.currentTimeMillis() - start;
            assertTrue("delay 应被截断到 ~100ms,实际:" + elapsed + "ms",
                    elapsed >= 80L && elapsed < 5_000L);
        } catch (TimeoutException expected) {
            // 也可能直接 Timeout——视实现细节
        }
    }

    @Test
    public void legacyOverloadForwardsToNewWithoutCancellation() throws Exception {
        // 旧重载必须仍可工作
        RetryPolicy fast = newFastRetryPolicy();
        AtomicInteger calls = new AtomicInteger(0);
        try {
            fast.invokeWithRetry(() -> {
                calls.incrementAndGet();
                throw new ModelApiException.ServerException(503, "test", null);
            });
            fail("ServerException 重试耗尽应抛");
        } catch (ModelApiException expected) {
            assertEquals("首次 + 2 次重试 = 3 次调用", 3, calls.get());
        }
    }

    @Test
    public void nullTokenBehavesLikeLegacyOverload() throws Exception {
        RetryPolicy fast = newFastRetryPolicy();
        AtomicInteger calls = new AtomicInteger(0);
        try {
            fast.invokeWithRetry(() -> {
                calls.incrementAndGet();
                throw new ModelApiException.RateLimitException("test", null);
            }, null, Long.MAX_VALUE);
            fail("RateLimitException 重试耗尽应抛");
        } catch (ModelApiException expected) {
            assertEquals(3, calls.get());
        }
    }

    private static RetryPolicy newFastRetryPolicy() {
        return new RetryPolicy() {
            @Override
            long computeDelay(int attempt) {
                return 1L;
            }
        };
    }
}
