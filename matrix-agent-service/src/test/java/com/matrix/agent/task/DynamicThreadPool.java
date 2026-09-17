package com.matrix.agent.task;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 可弹性扩缩的共享线程池,供 TaskScheduler / ModelCallExecutor / ToolExecutor 复用。
 *
 * <p><b>设计</b>:
 * <ul>
 *   <li>corePoolSize / maxPoolSize 可配,默认 core=2, max=8(车机 4 核 SoC 4 倍余量);</li>
 *   <li>队列 {@code LinkedBlockingQueue(capacity=32)},满后扩到 maxPoolSize,仍满走 CallerRunsPolicy
 *       反压(让调用线程执行,不丢任务,不死锁);</li>
 *   <li>keepAlive=60s,允许 core 线程空闲回收(让池子真能缩到 0);</li>
 *   <li>守护线程 + 命名 matrix-pool-N,便于 logcat / dumpsys 排查。</li>
 * </ul>
 *
 * <p><b>为什么不直接 Executors.newFixedThreadPool</b>:早期 TaskScheduler / ModelCallExecutor /
 * ToolExecutor 各自维护一个 fixed 池(2-4 线程),3 个池子在车机 4 核 SoC 上总 6-12 线程,峰值压力下
 * 互相竞争 CPU。DynamicThreadPool 单池共享 + 弹性扩缩,统一监控 + 收敛线程数。
 *
 * <p><b>线程安全</b>:ThreadPoolExecutor 本身线程安全,可被多 executor 共享。
 */
public final class DynamicThreadPool {
    private static final int DEFAULT_CORE = 2;
    private static final int DEFAULT_MAX = 8;
    private static final int DEFAULT_QUEUE_CAPACITY = 32;
    private static final long DEFAULT_KEEP_ALIVE_SECONDS = 60L;

    private final ThreadPoolExecutor executor;

    public DynamicThreadPool() {
        this(DEFAULT_CORE, DEFAULT_MAX, DEFAULT_QUEUE_CAPACITY);
    }

    /**
     * @param corePoolSize    核心线程数(空闲也不回收,除非 allowCoreThreadTimeOut=true)
     * @param maxPoolSize     最大线程数(队列满后扩到上限)
     * @param queueCapacity   任务队列容量(满后触发扩到 maxPoolSize,仍满走 CallerRunsPolicy)
     */
    public DynamicThreadPool(int corePoolSize, int maxPoolSize, int queueCapacity) {
        this(corePoolSize, maxPoolSize, queueCapacity, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * 构造器重载,允许指定 RejectedExecutionHandler。
     *
     * <p>默认重载仍用 CallerRunsPolicy(向后兼容);测试用 AbortPolicy 让队列满时
     * 显式抛 RejectedExecutionException,验证 TaskScheduler / ModelCallExecutor / ToolExecutor
     * 的 catch + REJECTED / POLICY_HALT / EXECUTION_UNKNOWN 兜底路径。
     *
     * @param corePoolSize    核心线程数
     * @param maxPoolSize     最大线程数
     * @param queueCapacity   队列容量
     * @param handler         拒绝策略(CallerRunsPolicy 反压 / AbortPolicy 显式抛 / DiscardPolicy 丢)
     */
    public DynamicThreadPool(int corePoolSize, int maxPoolSize, int queueCapacity,
            RejectedExecutionHandler handler) {
        if (corePoolSize <= 0) throw new IllegalArgumentException("corePoolSize 必须大于 0");
        if (maxPoolSize < corePoolSize) {
            throw new IllegalArgumentException("maxPoolSize 不能小于 corePoolSize");
        }
        if (queueCapacity <= 0) throw new IllegalArgumentException("queueCapacity 必须大于 0");
        if (handler == null) throw new IllegalArgumentException("handler 不能为空");
        BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>(queueCapacity);
        ThreadFactory factory = new PoolThreadFactory();
        this.executor = new ThreadPoolExecutor(
                corePoolSize, maxPoolSize, DEFAULT_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
                queue, factory, handler);
        // 允许 core 线程在 keepAlive 内空闲回收(让池子真能缩到 0)
        this.executor.allowCoreThreadTimeOut(true);
    }

    /**
     * 暴露底层 ThreadPoolExecutor 给 TaskScheduler / ModelCallExecutor /
     * ToolExecutor 的现有 {@code ExecutorService workers} 字段。签名不变,只是底层换成共享池。
     */
    public ExecutorService asExecutorService() {
        return executor;
    }

    /** 监控用:当前正在执行的任务数(近似值)。 */
    public int getActiveCount() {
        return executor.getActiveCount();
    }

    /** 监控用:当前池大小(含 core + 扩出的线程)。 */
    public int getPoolSize() {
        return executor.getPoolSize();
    }

    /** 监控用:队列中等待的任务数。 */
    public int getQueueSize() {
        return executor.getQueue().size();
    }

    /** 测试 / shutdown 钩子:优雅关闭,等待已提交任务完成。 */
    public void shutdown() {
        executor.shutdown();
    }

    /** 测试用:await termination(默认按 timeoutSeconds)。 */
    public boolean awaitTermination(long timeoutSeconds) throws InterruptedException {
        return executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS);
    }

    private static final class PoolThreadFactory implements ThreadFactory {
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "matrix-pool-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
