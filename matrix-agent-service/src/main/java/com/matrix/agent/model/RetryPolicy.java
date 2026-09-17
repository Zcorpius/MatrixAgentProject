package com.matrix.agent.model;

import android.util.Log;

import com.matrix.agent.task.identity.CancellationToken;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;

/**
 * 模型 API 受控重试策略——对 {@link ModelApiException.RateLimitException}
 * 与 {@link ModelApiException.ServerException} 做指数退避重试,4xx / 网络异常 / 超时不重试。
 *
 * <p><b>策略</b>:
 * <ul>
 *   <li>最大尝试次数 {@value #MAX_ATTEMPTS}(首次 + 2 次重试);</li>
 *   <li>初始延迟 {@value #INITIAL_DELAY_MS},退避因子 {@value #BACKOFF_FACTOR}
 *       (500ms → 1000ms → 2000ms);</li>
 *   <li>{@value #JITTER} jitter 避免雷鸣群(并发重试打到同一 Provider);</li>
 *   <li>非 retryable 异常直接向上抛(不消耗重试次数)。</li>
 * </ul>
 *
 * <p>新增 {@link #invokeWithRetry(CallableWithRetry, CancellationToken, long)}
 * 重载——退避期间感知 cancellation(立即唤醒 sleep + 抛 CancellationException),并按剩余 deadline
 * 截断 delay(剩余时间不足时直接抛 TimeoutException,避免"退避后剩余时间不够仍发请求")。
 *
 * <p><b>用法</b>:
 * <pre>{@code
 * RetryPolicy policy = new RetryPolicy();
 * return policy.invokeWithRetry(() -> client.callAnthropic(...));
 * }</pre>
 *
 * <p><b>线程安全</b>:无状态,可被多 ModelApiClient 实例共享。
 */
public class RetryPolicy {
    private static final String TAG = "MatrixAgent";

    /** 最大尝试次数——首次 + 2 次重试(共 3 次尝试)。 */
    public static final int MAX_ATTEMPTS = 3;
    /** 初始退避延迟——500ms。 */
    public static final long INITIAL_DELAY_MS = 500L;
    /** 退避因子——每次重试延迟乘以 2.0。 */
    public static final double BACKOFF_FACTOR = 2.0;
    /** Jitter 比例——±20% 抖动,避免雷鸣群。 */
    public static final double JITTER = 0.2;

    /** 函数式调用包装——catch + 重试 + 退避。 */
    public interface CallableWithRetry<T> {
        T call() throws Exception;
    }

    /**
     * 包装 call() 调用,对 retryable 异常做指数退避重试。
     *
     * @param action 业务调用(可能抛 ModelApiException)
     * @return action 返回值
     * @throws Exception 非 retryable 异常 / 重试次数耗尽时抛最后一次异常
     */
    public <T> T invokeWithRetry(CallableWithRetry<T> action) throws Exception {
        return invokeWithRetry(action, null, Long.MAX_VALUE);
    }

    /**
     * cancel + deadline 感知的重试入口。
     *
     * <p>每次 retry 前检查:
     * <ul>
     *   <li>{@code token.isCancelled()} → 抛 {@link CancellationException},不再 sleep / retry;</li>
     *   <li>{@code remaining = deadlineAtMillis - now()};{@code remaining <= 0} → 抛
     *       {@link TimeoutException};否则 sleep 时间 = {@code min(computeDelay, remaining)}。</li>
     * </ul>
     *
     * <p>sleep 期间若 token 被 cancel,通过 abort hook 把当前线程 interrupt,让
     * {@link Thread#sleep(long)} 立即抛 {@link InterruptedException}——catch 后转
     * {@link CancellationException}。这样 cancel 不需等待退避结束(原实现最坏情况要等
     * 500+1000+2000=3500ms)。
     *
     * @param action 业务调用
     * @param token  取消令牌(null 表示不感知 cancel,与旧重载等价)
     * @param deadlineAtMillis deadline 绝对时间戳(ms);{@link Long#MAX_VALUE} 表示不限制
     * @throws CancellationException token 已 cancel
     * @throws TimeoutException deadline 已过 / 剩余时间不足以再 retry
     * @throws Exception 非 retryable 异常 / 重试次数耗尽时抛最后一次异常
     */
    public <T> T invokeWithRetry(CallableWithRetry<T> action, CancellationToken token,
            long deadlineAtMillis) throws Exception {
        Exception lastException = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            // retry 前 cancel / deadline 检查(attempt > 1 时 retry 才有意义)
            if (attempt > 1) {
                if (token != null && token.isCancelled()) {
                    throw new CancellationException(
                            "retry aborted by cancellation token at attempt=" + attempt);
                }
                long remaining = deadlineAtMillis - System.currentTimeMillis();
                if (remaining <= 0L) {
                    throw new TimeoutException("retry aborted by deadline at attempt=" + attempt
                            + " remainingMs=" + remaining);
                }
            }
            try {
                return action.call();
            } catch (ModelApiException ex) {
                lastException = ex;
                if (!ex.isRetryable() || attempt >= MAX_ATTEMPTS) {
                    throw ex;
                }
                long rawDelay = computeDelay(attempt);
                long delay;
                String delayReason;
                if (token != null && token.isCancelled()) {
                    throw new CancellationException(
                            "retry aborted by cancellation token before sleep at attempt=" + attempt);
                }
                long remaining = deadlineAtMillis - System.currentTimeMillis();
                if (remaining <= 0L) {
                    throw new TimeoutException("retry aborted by deadline before sleep at attempt="
                            + attempt + " remainingMs=" + remaining);
                }
                if (rawDelay > remaining) {
                    delay = remaining;
                    delayReason = "truncated to remaining=" + remaining + "ms";
                } else {
                    delay = rawDelay;
                    delayReason = "natural";
                }
                Log.w(TAG, "[Retry] attempt=" + attempt + "/" + MAX_ATTEMPTS
                        + " type=" + ex.getClass().getSimpleName()
                        + " status=" + ex.statusCode
                        + " backingOff=" + delay + "ms (" + delayReason + ")");
                sleepWithCancel(delay, token);
            } catch (Exception ex) {
                // 非 ModelApiException(如 JSONException / IOException 直接包装)——不重试,直接抛
                throw ex;
            }
        }
        // 逻辑上不会到这里,但 Java 编译器要求
        throw new IllegalStateException("retry loop exhausted", lastException);
    }

    /**
     * cancel 感知的 sleep。
     *
     * <p>注册 abort hook,token.cancel() 时立即 interrupt 当前线程,Thread.sleep 抛
     * InterruptedException,catch 后转 CancellationException。token == null 时普通 sleep。
     */
    private void sleepWithCancel(long delayMs, CancellationToken token)
            throws CancellationException, TimeoutException {
        if (token == null) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new CancellationException("sleep interrupted (no token)");
            }
            return;
        }
        // 注册 abort hook:cancel 时立即 interrupt 当前线程
        Thread sleeper = Thread.currentThread();
        Runnable hook = () -> sleeper.interrupt();
        token.registerAbortHook(hook);
        try {
            // 双重检查:hook 注册后 token 可能已被 cancel(race),hook 已同步执行 interrupt
            if (token.isCancelled()) {
                throw new CancellationException("token cancelled during sleep registration");
            }
            Thread.sleep(delayMs);
            // sleep 正常结束——若此时 interrupt flag 被设置(晚到的 cancel),仍抛 CancellationException
            if (token.isCancelled()) {
                throw new CancellationException("token cancelled near end of sleep");
            }
        } catch (InterruptedException ie) {
            // interrupt flag 已被 clear(catch 时 JVM 自动),重设以便上层感知
            Thread.currentThread().interrupt();
            if (token.isCancelled()) {
                throw new CancellationException("sleep interrupted by cancellation token");
            }
            // 非 cancel 引起的 interrupt——少见,保守按 cancel 处理
            throw new CancellationException("sleep interrupted unexpectedly");
        } finally {
            token.removeAbortHook(hook);
        }
    }

    /** 计算第 attempt 次重试的延迟(指数退避 + jitter)。可由子类覆盖(测试用 1ms)。 */
    long computeDelay(int attempt) {
        // attempt 从 1 开始;首次重试用 INITIAL_DELAY_MS
        double base = INITIAL_DELAY_MS * Math.pow(BACKOFF_FACTOR, attempt - 1);
        double jitter = base * JITTER * (ThreadLocalRandom.current().nextDouble() * 2 - 1);
        return Math.max(1L, (long) (base + jitter));
    }
}
