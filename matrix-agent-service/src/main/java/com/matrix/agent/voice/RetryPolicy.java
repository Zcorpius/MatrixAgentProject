package com.matrix.agent.voice;

import com.matrix.agent.voice.port.*;

import java.io.IOException;
import java.util.function.BooleanSupplier;

/**
 * 退避重试策略(纯 Java,P2-2)。注入 {@link Clock}/{@link Sleeper} 使重试时序可测(免依赖真实
 * nanoTime/sleep);body 由调用方提供(封装"接管缺失模型"逻辑)。
 *
 * <p>{@link #retry} 重试 body.attempt():成功(不抛)→ 返回 true;抛 {@link RetryableFailure}(锁竞争)→
 * 继续退避;抛其他 {@link IOException}(真实失败)→ 向上传播;shouldStop 或超时 → 返回 false。
 */
public final class RetryPolicy {

    @FunctionalInterface
    public interface Clock {
        long nanoTime();
    }

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    @FunctionalInterface
    public interface Body {
        void attempt() throws IOException, RetryableFailure;
    }

    /** 仍忙(锁竞争等可重试状况)→ 继续退避,不算真实失败。 */
    public static final class RetryableFailure extends Exception {
        public RetryableFailure() { }
    }

    /**
     * 退避重试:成功(body 不抛)→ true;shouldStop 或超时(deadlineNs)→ false;真实 IOException → 传播。
     *
     * @param deadlineNs 截止时刻(clock.nanoTime() 单位)
     * @param delays     退避序列(之后固定用最后一项)
     * @param shouldStop 取消条件(返回 true 则立即退出返回 false)
     * @param body        每轮执行的尝试(抛 RetryableFailure 表示仍忙)
     */
    public static boolean retry(Clock clock, Sleeper sleeper, long deadlineNs, long[] delays,
            BooleanSupplier shouldStop, Body body) throws InterruptedException, IOException {
        if (delays == null || delays.length == 0) {
            throw new IllegalArgumentException("delays 不能为空");
        }
        int attempt = 0;
        while (!shouldStop.getAsBoolean() && clock.nanoTime() < deadlineNs) {
            // P2-1: sleep 截断为剩余时间(防越界——剩余 0.5s 不应 sleep 2s)
            long remainingNs = deadlineNs - clock.nanoTime();
            long plannedMs = attempt < delays.length ? delays[attempt] : delays[delays.length - 1];
            long sleepMs = Math.min(plannedMs, remainingNs / 1_000_000L);
            if (sleepMs > 0) sleeper.sleep(sleepMs);
            attempt++;
            // P2-1: sleep 后复查 deadline(防越界后仍执行 body.attempt,甚至成功返回 true)
            if (shouldStop.getAsBoolean() || clock.nanoTime() >= deadlineNs) {
                return false;
            }
            try {
                body.attempt();
                return true; // 成功
            } catch (RetryableFailure b) {
                continue; // 仍忙,继续退避
            }
        }
        return false; // 取消或超时
    }

    private RetryPolicy() { }
}
