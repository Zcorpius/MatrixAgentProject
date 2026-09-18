package com.matrix.agent.host;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Host 持有的全局线程预算登记处（重整版 §2.4.1）：所有长生命周期的业务线程池
 * 统一在此创建、命名与关停，业务域不得自行 {@code Executors.new*}。
 *
 * <p>预算：15 个有界业务 worker、3 个单线程有界 scheduler 与 1 个实时采音线程，
 * Java 线程总上限为 19（不含 MNN native 内部线程）：
 * <pre>
 * task 2/32 · network/download 4/16 · mnn 1/2 · db 1/32 · host-dispatch 2/16
 * voice 流水线 5（download/state/agent/lifecycle/lifecycle 五个串行单线程）+ capture 1：
 *   五段是会话状态机的串行隔离边界，存在受控的跨段提交，合并为单 worker
 *   会引入嵌套等待死锁，故按域单独成池（2026-09-15 审计 A-113 收敛时修订）。
 * timer/audit 1/32 · voice-timeout 1/16 · mnn-retire 1/8。
 * </pre>
 * 全部拒绝策略为 AbortPolicy：队列满时显式失败并产生可观察终态，不入无界队列。
 */
public final class MatrixExecutorRegistry {

    private final List<ExecutorService> allExecutors = new ArrayList<>();
    private final List<ScheduledExecutorService> allSchedulers = new ArrayList<>();

    private final ExecutorService taskExecutor;
    private final ExecutorService networkExecutor;
    private final ExecutorService modelExecutor;
    private final ExecutorService dbExecutor;
    private final ExecutorService hostDispatcherExecutor;
    private final ScheduledExecutorService timerScheduler;
    private final ScheduledExecutorService modelRetirementScheduler;

    private final ExecutorService voiceDownloadExecutor;
    private final ExecutorService voiceStateExecutor;
    private final ExecutorService voiceAgentExecutor;
    private final ExecutorService voiceLifecycleExecutor;
    private final ExecutorService lifecycleExecutor;
    private final ScheduledExecutorService voiceTimeoutScheduler;
    /** A VoiceCaptureController has at most one active session; its real-time read loop needs a Thread. */
    private final ThreadFactory voiceCaptureThreadFactory;

    public MatrixExecutorRegistry() {
        taskExecutor = newBoundedPool("matrix-task", 2, 32);
        networkExecutor = newBoundedPool("matrix-network", 4, 16);
        modelExecutor = newBoundedPool("matrix-mnn", 1, 2);
        dbExecutor = newBoundedPool("matrix-db", 1, 32);
        // Host work waits for TaskScheduler futures, so it must never share the scheduler pool.
        hostDispatcherExecutor = newBoundedPool("matrix-host-dispatch", 2, 16);
        timerScheduler = newBoundedScheduler("matrix-timer", 32);
        // Native release retry must not be starved by catalog/audit retry storms.
        modelRetirementScheduler = newBoundedScheduler("matrix-model-retire", 8);
        allExecutors.add(taskExecutor);
        allExecutors.add(networkExecutor);
        allExecutors.add(modelExecutor);
        allExecutors.add(dbExecutor);
        allExecutors.add(hostDispatcherExecutor);
        allSchedulers.add(timerScheduler);
        allSchedulers.add(modelRetirementScheduler);

        voiceDownloadExecutor = newSingleBounded("matrix-voice-dl", 16);
        voiceStateExecutor = newSingleBounded("matrix-voice-state", 16);
        voiceAgentExecutor = newSingleBounded("matrix-voice-agent", 16);
        voiceLifecycleExecutor = newSingleBounded("matrix-voice-lifecycle", 8);
        lifecycleExecutor = newSingleBounded("matrix-lifecycle", 8);
        voiceTimeoutScheduler = newBoundedScheduler("matrix-voice-timeout", 16);
        voiceCaptureThreadFactory = daemonFactory("matrix-voice-capture");
        allExecutors.add(voiceDownloadExecutor);
        allExecutors.add(voiceStateExecutor);
        allExecutors.add(voiceAgentExecutor);
        allExecutors.add(voiceLifecycleExecutor);
        allExecutors.add(lifecycleExecutor);
        allSchedulers.add(voiceTimeoutScheduler);
    }

    /** Agent 任务调度（TaskScheduler 域）。 */
    public ExecutorService taskExecutor() {
        return taskExecutor;
    }

    /** 网络/下载域（模型 API、catalog、模型与语音包下载共用 4/16 预算）。 */
    public ExecutorService networkExecutor() {
        return networkExecutor;
    }

    /** 端侧 MNN 推理串行池。 */
    public ExecutorService modelExecutor() {
        return modelExecutor;
    }

    /** 数据库短事务池。 */
    public ExecutorService dbExecutor() {
        return dbExecutor;
    }

    /** Host dispatches durable requests here; it may wait on taskExecutor but never shares it. */
    public ExecutorService hostDispatcherExecutor() {
        return hostDispatcherExecutor;
    }

    /** 通用定时/重试调度。 */
    public ScheduledExecutorService timerScheduler() {
        return timerScheduler;
    }

    /** Dedicated bounded retry lane for retiring a native model session after cancellation. */
    public ScheduledExecutorService modelRetirementScheduler() {
        return modelRetirementScheduler;
    }

    public ExecutorService voiceDownloadExecutor() {
        return voiceDownloadExecutor;
    }

    public ExecutorService voiceStateExecutor() {
        return voiceStateExecutor;
    }

    public ExecutorService voiceAgentExecutor() {
        return voiceAgentExecutor;
    }

    public ExecutorService voiceLifecycleExecutor() {
        return voiceLifecycleExecutor;
    }

    /** Long-running lifecycle/drain work; isolated from request and model I/O paths. */
    public ExecutorService lifecycleExecutor() {
        return lifecycleExecutor;
    }

    public ScheduledExecutorService voiceTimeoutScheduler() {
        return voiceTimeoutScheduler;
    }

    /** Factory for the one session-owned real-time capture loop; it is not a general worker pool. */
    public ThreadFactory voiceCaptureThreadFactory() {
        return voiceCaptureThreadFactory;
    }

    /** Host 销毁时统一关停；先排干任务再强制中断。 */
    public void shutdown() {
        for (ExecutorService executor : allExecutors) {
            executor.shutdown();
        }
        for (ScheduledExecutorService scheduler : allSchedulers) {
            scheduler.shutdown();
        }
        for (ExecutorService executor : allExecutors) {
            try {
                if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }
        for (ScheduledExecutorService scheduler : allSchedulers) {
            scheduler.shutdownNow();
        }
    }

    private static ExecutorService newBoundedPool(String name, int threads, int queueCapacity) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(threads, threads,
                0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(queueCapacity),
                daemonFactory(name), new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }

    /** 单 worker 有界池：与 newSingleThreadExecutor 等价的串行语义，但队列有界 + Abort。 */
    private static ExecutorService newSingleBounded(String name, int queueCapacity) {
        return newBoundedPool(name, 1, queueCapacity);
    }

    /**
     * Delayed-work queues in Executors.newSingleThreadScheduledExecutor are unbounded.  Bound the
     * number of outstanding scheduled tasks with a semaphore so a retry storm cannot consume
     * unbounded memory.  One-shot tasks release the permit after execution; periodic tasks retain
     * it until cancellation, which is exactly one slot per registered periodic job.
     */
    private static ScheduledExecutorService newBoundedScheduler(String name, int maxPending) {
        return new BoundedScheduledExecutor(name, maxPending);
    }

    private static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger index = new AtomicInteger(1);
        return r -> {
            Thread t = new Thread(r, prefix + "-" + index.getAndIncrement());
            t.setDaemon(true);
            return t;
        };
    }
}
