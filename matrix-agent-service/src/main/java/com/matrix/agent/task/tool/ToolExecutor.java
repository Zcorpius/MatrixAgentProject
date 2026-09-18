package com.matrix.agent.task.tool;

import com.matrix.agent.identity.AgentRequest;

import android.util.Log;

import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.task.capability.*;
import com.matrix.agent.identity.*;
import com.matrix.agent.intent.*;
import com.matrix.agent.vehicle.*;


import java.util.Collections;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/** Enforces per-capability timeout and propagates cancellation around provider calls. */
public final class ToolExecutor {
    private static final String TAG = "MatrixAgent";
    private static final long CANCELLATION_POLL_MILLIS = 50L;
    private final ExecutorService workers;
    /** true 时 shutdown() 才真正关闭池;共享池由外部统一关闭。 */
    private final boolean ownsPool;

    /** Test-only convenience constructor; Host runtime injects its bounded network lane. */
    public ToolExecutor(int parallelism) {
        this(parallelism, null, "test-owned");
    }

    /** Uses the Host registry's I/O executor while retaining the legacy constructor for tests. */
    public ToolExecutor(int parallelism, ExecutorService sharedExecutor) {
        this(parallelism, sharedExecutor, "HostExecutor");
    }

    private ToolExecutor(int parallelism, ExecutorService sharedExecutor, String sharedExecutorName) {
        if (parallelism <= 0) throw new IllegalArgumentException("parallelism 必须大于 0");
        if (sharedExecutor != null) {
            this.workers = sharedExecutor;
            this.ownsPool = false;
            Log.i(TAG, "[Tool] init parallelism=" + parallelism
                    + " sharedPool=" + sharedExecutorName);
        } else {
            this.workers = Executors.newFixedThreadPool(parallelism, new ToolThreadFactory());
            this.ownsPool = true;
            Log.i(TAG, "[Tool] init parallelism=" + parallelism);
        }
    }

    /** 测试 / shutdown 钩子:优雅关闭 worker 线程池。共享池不在此处关闭。 */
    public void shutdown() {
        if (ownsPool) {
            workers.shutdown();
        }
    }

    public ToolResult execute(CapabilityProvider provider, CapabilityDefinition definition,
            AgentRequest request, ToolCall call) {
        long started = System.nanoTime();
        long budgetMillis = Math.min(definition.getTimeoutMillis(), request.remainingMillis());
        if (budgetMillis <= 0) {
            Log.w(TAG, "[Tool] pre-call deadline passed cap=" + call.getCapabilityName()
                    + " id=" + call.getStepId());
            return resolveTerminal(call, definition, "任务已超过截止时间", started,
                    TerminalReason.DEADLINE_PASSED);
        }

        Log.d(TAG, "[Tool] submit cap=" + call.getCapabilityName()
                + " id=" + call.getStepId()
                + " budgetMs=" + budgetMillis
                + " (capability=" + definition.getTimeoutMillis()
                + " request=" + request.remainingMillis() + ")");
        Future<ToolResult> future;
        try {
            future = workers.submit(() -> provider.execute(request, call));
        } catch (RejectedExecutionException rejected) {
            // ioPool 队列满 / Executor 关闭时,submit 抛 RejectedExecutionException。
            // 写操作走 EXECUTION_UNKNOWN(命令可能未下发,但保守按"未知"提示用户二次确认);
            // 读操作走 CANCELLED(语义同 TIMED_OUT 但显式标 REJECTED)。
            Log.w(TAG, "[Tool] submit REJECTED cap=" + call.getCapabilityName()
                    + " id=" + call.getStepId() + " (ioPool 队列满)");
            return resolveTerminal(call, definition, "Tool executor 队列满(REJECTED)", started,
                    TerminalReason.REJECTED);
        }
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis);
        try {
            while (true) {
                if (request.isCancelled()) {
                    future.cancel(true);
                    tryAbort(provider, definition, call);
                    Log.w(TAG, "[Tool] cancellation observed cap=" + call.getCapabilityName()
                            + " id=" + call.getStepId() + " -> " + terminalLabelFor(definition));
                    return resolveTerminal(call, definition, "Capability 已取消", started,
                            TerminalReason.CANCELLED);
                }
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    future.cancel(true);
                    tryAbort(provider, definition, call);
                    Log.w(TAG, "[Tool] capability timeout cap=" + call.getCapabilityName()
                            + " id=" + call.getStepId() + " budgetMs=" + budgetMillis
                            + " -> " + terminalLabelFor(definition));
                    return resolveTerminal(call, definition,
                            "Capability 执行超过 " + budgetMillis + " ms", started,
                            TerminalReason.TIMED_OUT);
                }
                long waitNanos = Math.min(remainingNanos,
                        TimeUnit.MILLISECONDS.toNanos(CANCELLATION_POLL_MILLIS));
                try {
                    ToolResult result = future.get(waitNanos, TimeUnit.NANOSECONDS);
                    Log.d(TAG, "[Tool] <- cap=" + call.getCapabilityName()
                            + " id=" + call.getStepId()
                            + " status=" + result.getStatus()
                            + " verified=" + result.isVerified()
                            + " durationMs=" + result.getDurationMillis());
                    return result;
                } catch (TimeoutException ignored) {
                    // Poll cancellation until the capability or request deadline is reached.
                }
            }
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            tryAbort(provider, definition, call);
            Thread.currentThread().interrupt();
            Log.w(TAG, "[Tool] worker thread interrupted cap=" + call.getCapabilityName()
                    + " -> " + terminalLabelFor(definition));
            return resolveTerminal(call, definition, "Capability 已取消", started,
                    TerminalReason.INTERRUPTED);
        } catch (ExecutionException execution) {
            Throwable cause = execution.getCause() == null ? execution : execution.getCause();
            Log.e(TAG, "[Tool] provider threw cap=" + call.getCapabilityName()
                    + " id=" + call.getStepId() + " " + cause.getClass().getSimpleName()
                    + ": " + safeMessage(cause) + " -> EXECUTION_FAILED", execution);
            return new ToolResult(ToolResult.Status.EXECUTION_FAILED, call.getCapabilityName(),
                    "Capability 执行异常：" + safeMessage(cause), Collections.emptyMap(), false,
                    elapsedMillis(started));
        }
    }

    /**
     * 写操作 timeout / cancel 时尽量 abort 已下发的命令。
     *
     * <p>调用 {@link CapabilityProvider#abortIfSupported(String)} + 记录 log,但<b>不依赖</b>
     * abort 是否成功——Provider 可能在 abort 到达前已执行,或 ECU 拒绝中断。最终状态语义
     * 仍由 {@link #resolveTerminal} 决定:写操作一律 EXECUTION_UNKNOWN。
     */
    private static void tryAbort(CapabilityProvider provider, CapabilityDefinition definition,
            ToolCall call) {
        if (!definition.isWriteOperation()) return;
        if (!provider.isAbortable()) return;
        String commandId = call.getStepId();
        try {
            provider.abortIfSupported(commandId);
            Log.i(TAG, "[Tool] abortIfSupported invoked cap=" + call.getCapabilityName()
                    + " id=" + commandId + " (abort 不保证成功,readback 需二次确认)");
        } catch (Throwable abortError) {
            Log.w(TAG, "[Tool] abortIfSupported threw cap=" + call.getCapabilityName()
                    + " id=" + commandId + " cause=" + abortError.getClass().getSimpleName()
                    + ": " + safeMessage(abortError) + " (忽略,仍按 EXECUTION_UNKNOWN 处理)");
        }
    }

    /**
     * 按 capability 类型决定终态语义。
     *
     * <ul>
     *   <li>读操作(默认 writeOperation=false):继续返回 TIMED_OUT / CANCELLED——读被中断
     *       等于未发生,行为不变。</li>
     *   <li>写操作(writeOperation=true):Java {@code future.cancel(true)} 中断线程
     *       <b>不保证</b>撤销已发出的 IPC 命令,Runtime 不能宣称"已取消",一律返回
     *       {@link ToolResult.Status#EXECUTION_UNKNOWN};后续版本由 readback
     *       / queryCommandState 异步更新为 SUCCESS / VERIFICATION_FAILED / UNKNOWN。</li>
     * </ul>
     */
    private ToolResult resolveTerminal(ToolCall call, CapabilityDefinition definition,
            String message, long started, TerminalReason reason) {
        if (definition.isWriteOperation()) {
            return executionUnknown(call, message + "(命令已下发,执行结果未知)", started, reason);
        }
        switch (reason) {
            case TIMED_OUT:
            case DEADLINE_PASSED:
                return timedOut(call, message, started);
            case CANCELLED:
            case INTERRUPTED:
            case REJECTED:
            default:
                return cancelled(call, started);
        }
    }

    private static String terminalLabelFor(CapabilityDefinition definition) {
        return definition.isWriteOperation() ? "EXECUTION_UNKNOWN" : "CANCELLED / TIMED_OUT";
    }

    private enum TerminalReason {
        DEADLINE_PASSED,
        TIMED_OUT,
        CANCELLED,
        INTERRUPTED,
        /** ioPool 队列满 / Executor 拒绝——任务未真正下发。 */
        REJECTED
    }

    private static ToolResult timedOut(ToolCall call, String message, long started) {
        return new ToolResult(ToolResult.Status.TIMED_OUT, call.getCapabilityName(), message,
                Collections.emptyMap(), false, elapsedMillis(started));
    }

    private static ToolResult cancelled(ToolCall call, long started) {
        return new ToolResult(ToolResult.Status.CANCELLED, call.getCapabilityName(), "Capability 已取消",
                Collections.emptyMap(), false, elapsedMillis(started));
    }

    private static ToolResult executionUnknown(ToolCall call, String message, long started,
            TerminalReason reason) {
        String suffix = reason == TerminalReason.TIMED_OUT || reason == TerminalReason.DEADLINE_PASSED
                ? "timeout"
                : (reason == TerminalReason.REJECTED ? "rejected" : "cancelled");
        return new ToolResult(ToolResult.Status.EXECUTION_UNKNOWN, call.getCapabilityName(),
                "写操作" + suffix + ":" + message,
                Collections.emptyMap(), false, elapsedMillis(started));
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private static final class ToolThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "matrix-tool-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
