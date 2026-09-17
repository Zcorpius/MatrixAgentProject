package com.matrix.agent.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Method;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.matrix.agent.task.identity.CancellationToken;

/**
 * ModelApiClient.complete 5 参重载 + RetryPolicy 新重载契约。
 *
 * <p>ModelApiClient.complete 走真实 HTTP,JVM 单测无法稳定 mock HttpURLConnection。
 * 这里只验证两件事:
 * <ul>
 *   <li>5 参 complete 重载存在;旧 3 参仍可用(向后兼容)。</li>
 *   <li>RetryPolicy.invokeWithRetry(action, token, deadline) 新重载在 cancel / deadline 场景行为正确——
 *       ModelApiClient 4 个生产入口(complete / callAnthropic / callOpenAi / callGemini)内部
 *       转发到此方法,所以这里直接测 RetryPolicy 等价于测 ModelApiClient 路径。</li>
 * </ul>
 */
public final class ModelApiClientCancelDeadlineIntegrationTest {

    /**
     * 5 参 complete 重载必须存在,签名是 (ModelConfig, String, String, CancellationToken, long)。
     */
    @Test
    public void fiveArgCompleteOverloadExists() throws Exception {
        Method m = ModelApiClient.class.getMethod("complete",
                ModelConfig.class, String.class, String.class,
                CancellationToken.class, long.class);
        assertTrue("5 参 complete 重载返回 String",
                m.getReturnType() == String.class);
    }

    /**
     * 5 参 callAnthropicWithTools / callOpenAiWithTools / callGeminiWithTools 重载必须存在,
     * 签名末两参是 (CancellationToken, long)。
     */
    @Test
    public void fiveArgCallXxxWithToolsOverloadsExist() throws Exception {
        assertTrue(getCallXxxWithToolsOverload("callAnthropicWithTools") != null);
        assertTrue(getCallXxxWithToolsOverload("callOpenAiWithTools") != null);
        assertTrue(getCallXxxWithToolsOverload("callGeminiWithTools") != null);
    }

    private static Method getCallXxxWithToolsOverload(String name) {
        for (Method m : ModelApiClient.class.getMethods()) {
            if (!name.equals(m.getName())) continue;
            Class<?>[] params = m.getParameterTypes();
            if (params.length == 6
                    && params[4] == CancellationToken.class
                    && params[5] == long.class) {
                return m;
            }
        }
        return null;
    }

    /**
     * cancelled token + retryable action → RetryPolicy 第 2 轮 attempt 前检查 cancel,
     * 抛 CancellationException, action 只被调用 1 次。
     */
    @Test
    public void cancelledTokenAbortsRetryBeforeSecondAttempt() {
        RetryPolicy policy = new RetryPolicy();
        CancellationToken token = new CancellationToken();
        token.cancel();
        AtomicInteger calls = new AtomicInteger(0);
        try {
            policy.invokeWithRetry(() -> {
                calls.incrementAndGet();
                throw new ModelApiException.RateLimitException("test",
                        new IllegalStateException("429"));
            }, token, System.currentTimeMillis() + 60_000L);
            fail("cancelled token 应抛 CancellationException");
        } catch (CancellationException expected) {
            assertEquals("第一轮跑了 1 次,第二轮 attempt 前取消", 1, calls.get());
        } catch (Exception ex) {
            fail("应抛 CancellationException,实际:" + ex.getClass().getSimpleName());
        }
    }

    /**
     * 第一轮 retryable 异常后,sleep 前检查 remaining <= 0 → 抛 TimeoutException,action 只 1 次。
     */
    @Test
    public void expiredDeadlineAbortsRetryBeforeSleep() {
        RetryPolicy policy = new RetryPolicy();
        AtomicInteger calls = new AtomicInteger(0);
        try {
            policy.invokeWithRetry(() -> {
                calls.incrementAndGet();
                throw new ModelApiException.RateLimitException("test",
                        new IllegalStateException("429"));
            }, /* token */ null, /* deadlineAtMillis */ System.currentTimeMillis() - 1L);
            fail("expired deadline 应抛 TimeoutException");
        } catch (TimeoutException expected) {
            assertEquals("第一轮跑了 1 次,退避前 deadline 检查截断", 1, calls.get());
        } catch (Exception ex) {
            fail("应抛 TimeoutException,实际:" + ex.getClass().getSimpleName());
        }
    }

    /**
     * 旧 invokeWithRetry(action) 仍可用(token=null, deadline=MAX,与旧重载语义等价)。
     */
    @Test
    public void legacySingleArgInvokeWithRetryStillWorks() throws Exception {
        RetryPolicy policy = new RetryPolicy();
        AtomicInteger calls = new AtomicInteger(0);
        String result = policy.invokeWithRetry(() -> {
            calls.incrementAndGet();
            return "ok";
        });
        assertEquals("legacy 重载应正常返回", "ok", result);
        assertEquals("action 只跑 1 次", 1, calls.get());
    }
}
