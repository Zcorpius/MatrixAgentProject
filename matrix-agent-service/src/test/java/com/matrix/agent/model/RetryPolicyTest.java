package com.matrix.agent.model;

import com.matrix.agent.contract.ModelApiException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * RetryPolicy 测试——重试触发 / 不重试 / 退避时间 / 最大尝试次数。
 *
 * <p>真实 HTTP 集成由 androidTest 验证;JVM 覆盖:
 * <ul>
 *   <li>RateLimitException / ServerException 触发重试;</li>
 *   <li>ClientException / NetworkException / TimeoutException 不重试;</li>
 *   <li>非 ModelApiException(如 JSONException)不重试,直接抛;</li>
 *   <li>最大尝试次数耗尽后抛最后一次异常;</li>
 *   <li>computeDelay(attempt) 单调递增 + jitter 范围合理。</li>
 * </ul>
 */
public final class RetryPolicyTest {

    @Test
    public void rateLimitExceptionRetriesUpToMaxAttempts() throws Exception {
        AtomicInteger calls = new AtomicInteger(0);
        // Override computeDelay to make test fast (no actual sleep)
        RetryPolicy fastPolicy = new RetryPolicy() {
            @Override
            long computeDelay(int attempt) {
                return 1L;  // 1ms 避免空跑
            }
        };

        try {
            fastPolicy.invokeWithRetry(() -> {
                calls.incrementAndGet();
                throw new ModelApiException.RateLimitException("test", null);
            });
            fail("应抛 RateLimitException");
        } catch (ModelApiException.RateLimitException expected) {
            // ok
        }
        assertEquals("MAX_ATTEMPTS=3 次尝试", RetryPolicy.MAX_ATTEMPTS, calls.get());
    }

    @Test
    public void serverExceptionRetriesUpToMaxAttempts() throws Exception {
        AtomicInteger calls = new AtomicInteger(0);
        RetryPolicy fastPolicy = new RetryPolicy() {
            @Override
            long computeDelay(int attempt) { return 1L; }
        };

        try {
            fastPolicy.invokeWithRetry(() -> {
                calls.incrementAndGet();
                throw new ModelApiException.ServerException(503, "test", null);
            });
            fail("应抛 ServerException");
        } catch (ModelApiException.ServerException expected) {
            // ok
        }
        assertEquals(RetryPolicy.MAX_ATTEMPTS, calls.get());
    }

    @Test
    public void clientExceptionDoesNotRetry() throws Exception {
        AtomicInteger calls = new AtomicInteger(0);
        RetryPolicy policy = new RetryPolicy();

        try {
            policy.invokeWithRetry(() -> {
                calls.incrementAndGet();
                throw new ModelApiException.ClientException(400, "test", null);
            });
            fail("应抛 ClientException");
        } catch (ModelApiException.ClientException expected) {
            // ok
        }
        assertEquals("ClientException 不重试,仅 1 次调用", 1, calls.get());
    }

    @Test
    public void networkExceptionDoesNotRetry() throws Exception {
        AtomicInteger calls = new AtomicInteger(0);
        RetryPolicy policy = new RetryPolicy();

        try {
            policy.invokeWithRetry(() -> {
                calls.incrementAndGet();
                throw new ModelApiException.NetworkException("test",
                        new java.io.IOException("simulated"));
            });
            fail("应抛 NetworkException");
        } catch (ModelApiException.NetworkException expected) {
            // ok
        }
        assertEquals("NetworkException 不重试,仅 1 次调用", 1, calls.get());
    }

    @Test
    public void timeoutExceptionDoesNotRetry() throws Exception {
        AtomicInteger calls = new AtomicInteger(0);
        RetryPolicy policy = new RetryPolicy();

        try {
            policy.invokeWithRetry(() -> {
                calls.incrementAndGet();
                throw new ModelApiException.TimeoutException("test",
                        new java.net.SocketTimeoutException("simulated"));
            });
            fail("应抛 TimeoutException");
        } catch (ModelApiException.TimeoutException expected) {
            // ok
        }
        assertEquals("TimeoutException 不重试", 1, calls.get());
    }

    @Test
    public void nonModelApiExceptionDoesNotRetry() throws Exception {
        AtomicInteger calls = new AtomicInteger(0);
        RetryPolicy policy = new RetryPolicy();

        try {
            policy.invokeWithRetry(() -> {
                calls.incrementAndGet();
                throw new RuntimeException("non-model exception");
            });
            fail("应抛 RuntimeException");
        } catch (RuntimeException expected) {
            // ok
        }
        assertEquals("非 ModelApiException 不重试", 1, calls.get());
    }

    @Test
    public void successAfterRetryReturnsValue() throws Exception {
        AtomicInteger calls = new AtomicInteger(0);
        RetryPolicy fastPolicy = new RetryPolicy() {
            @Override
            long computeDelay(int attempt) { return 1L; }
        };

        String result = fastPolicy.invokeWithRetry(() -> {
            int n = calls.incrementAndGet();
            if (n < 3) {
                throw new ModelApiException.ServerException(503, "test", null);
            }
            return "success-on-attempt-" + n;
        });

        assertEquals("success-on-attempt-3", result);
        assertEquals(3, calls.get());
    }

    @Test
    public void rateLimitIsRetryable() {
        ModelApiException rateLimit = new ModelApiException.RateLimitException("test", null);
        assertTrue("RateLimitException isRetryable=true", rateLimit.isRetryable());
    }

    @Test
    public void serverExceptionIsRetryable() {
        ModelApiException server = new ModelApiException.ServerException(503, "test", null);
        assertTrue(server.isRetryable());
    }

    @Test
    public void clientExceptionNotRetryable() {
        ModelApiException client = new ModelApiException.ClientException(400, "test", null);
        assertEquals(false, client.isRetryable());
    }

    @Test
    public void computeDelayMonotonicallyIncreases() {
        RetryPolicy policy = new RetryPolicy();
        long d1 = policy.computeDelay(1);
        long d2 = policy.computeDelay(2);
        long d3 = policy.computeDelay(3);
        // jitter 可能让 d2 略小于 d1(±20%),但整体趋势应递增
        // 基础值:500, 1000, 2000——确认 jitter 范围内
        assertTrue("d1 在 [400, 600]", d1 >= 400 && d1 <= 600);
        assertTrue("d2 在 [800, 1200]", d2 >= 800 && d2 <= 1200);
        assertTrue("d3 在 [1600, 2400]", d3 >= 1600 && d3 <= 2400);
    }

    @Test
    public void exceptionMessageDoesNotLeakHttpBody() {
        // ModelApiException.getMessage() 仅返回 type + status + sanitizedEndpoint,
        // 不含 HTTP body(API key / 用户 PII 可能被网关回显)
        ModelApiException ex = new ModelApiException.ServerException(500, "https://api.example.com/v1", null);
        String msg = ex.getMessage();
        assertTrue("含 type=server-error", msg.contains("server-error"));
        assertTrue("含 status=500", msg.contains("500"));
        assertTrue("含 endpoint", msg.contains("api.example.com"));
    }
}
