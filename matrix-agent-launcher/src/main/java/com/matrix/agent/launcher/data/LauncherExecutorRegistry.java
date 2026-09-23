package com.matrix.agent.launcher.data;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Launcher process-owned worker budget.
 *
 * <p>The launcher is a separate APK, so it must never borrow Host workers.  Keeping the two
 * bounded lanes here makes that process boundary explicit and gives the Application one owner
 * for shutdown in tests and controlled process teardown.</p>
 */
public final class LauncherExecutorRegistry {
    private final ExecutorService sdkCalls = new ThreadPoolExecutor(2, 2, 0L,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16), daemonFactory("matrix-launcher-sdk"),
            new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledThreadPoolExecutor polling = new ScheduledThreadPoolExecutor(1,
            daemonFactory("matrix-launcher-poll"), new ThreadPoolExecutor.AbortPolicy());
    /**
     * 草稿命令后端（输入交互增强 §7.2）：DraftCommandLane 在此之上按 conversation
     * 实现 FIFO，不同 conversation 可并行。使用无界队列避免“用户输入保存”被一个很小
     * 的全局容量静默丢弃；背压由每会话 350ms debounce 与 Host 12KiB 上限提供。
     */
    private final ExecutorService draftCommands = new ThreadPoolExecutor(2, 2, 0L,
            TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
            daemonFactory("matrix-launcher-draft"), new ThreadPoolExecutor.AbortPolicy());

    public LauncherExecutorRegistry() {
        // Cancelled fragment polling must leave no delayed work behind after navigation.
        polling.setRemoveOnCancelPolicy(true);
        polling.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        polling.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }

    public ExecutorService sdkCalls() { return sdkCalls; }
    public ScheduledExecutorService polling() { return polling; }
    public ExecutorService draftCommands() { return draftCommands; }

    public void shutdown() {
        sdkCalls.shutdownNow();
        polling.shutdownNow();
        draftCommands.shutdownNow();
    }

    private static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger index = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + index.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }
}
