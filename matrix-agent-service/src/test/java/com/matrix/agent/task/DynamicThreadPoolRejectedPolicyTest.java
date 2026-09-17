package com.matrix.agent.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

/**
 * DynamicThreadPool RejectedExecutionHandler 构造器契约测试。
 *
 * <p>评审发现共享池嵌套等待死锁——三 executor 共享一个 DynamicThreadPool,
 * Scheduler 占线程等 model/tool 子任务,子任务在同池排队。改用两个独立池 + 显式拒绝策略。
 *
 * <p>本测试验证:
 * <ul>
 *   <li>新构造器 {@code DynamicThreadPool(core, max, queue, handler)} 接受自定义 handler;</li>
 *   <li>AbortPolicy 让队列满时显式抛 RejectedExecutionException;</li>
 *   <li>CallerRunsPolicy(默认重载)反压,调用线程执行任务。</li>
 * </ul>
 */
public final class DynamicThreadPoolRejectedPolicyTest {

    @Test
    public void abortPolicyThrowsRejectedExecutionExceptionWhenQueueFull() throws Exception {
        // core=1, max=1, queue=1——只能跑 1 个任务 + 队列 1 个,第 3 个 submit 应被 reject
        DynamicThreadPool pool = new DynamicThreadPool(1, 1, 1,
                new ThreadPoolExecutor.AbortPolicy());
        try {
            CountDownLatch blockFirst = new CountDownLatch(1);
            CountDownLatch firstStarted = new CountDownLatch(1);
            // 提交第 1 个:占住唯一线程,不结束
            pool.asExecutorService().submit(() -> {
                firstStarted.countDown();
                blockFirst.await();
                return null;
            });
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
            // 提交第 2 个:进队列(队列=1)
            pool.asExecutorService().submit(() -> null);
            // 提交第 3 个:队列满,AbortPolicy 抛 RejectedExecutionException
            try {
                pool.asExecutorService().submit(() -> null);
                fail("队列满 + AbortPolicy 应抛 RejectedExecutionException");
            } catch (RejectedExecutionException expected) {
                // 期望
            }
            blockFirst.countDown();
        } finally {
            pool.shutdown();
            pool.awaitTermination(2);
        }
    }

    @Test
    public void callerRunsPolicyRunsTaskInCallingThread() throws Exception {
        // core=1, max=1, queue=1——CallerRunsPolicy 时第 3 个任务在调用线程执行
        DynamicThreadPool pool = new DynamicThreadPool(1, 1, 1,
                new ThreadPoolExecutor.CallerRunsPolicy());
        try {
            String callerThreadName = Thread.currentThread().getName();
            CountDownLatch blockFirst = new CountDownLatch(1);
            CountDownLatch firstStarted = new CountDownLatch(1);
            pool.asExecutorService().submit(() -> {
                firstStarted.countDown();
                blockFirst.await();
                return null;
            });
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
            pool.asExecutorService().submit(() -> null);  // 进队列

            // 第 3 个任务:CallerRunsPolicy 在调用线程执行
            java.util.concurrent.atomic.AtomicReference<String> runnerName = new java.util.concurrent.atomic.AtomicReference<>();
            pool.asExecutorService().submit(() -> {
                runnerName.set(Thread.currentThread().getName());
                return null;
            });
            assertEquals("CallerRunsPolicy 让调用线程执行任务",
                    callerThreadName, runnerName.get());
            blockFirst.countDown();
        } finally {
            pool.shutdown();
            pool.awaitTermination(2);
        }
    }

    @Test
    public void defaultConstructorStillUsesCallerRunsPolicy() throws Exception {
        // 默认重载必须保持 CallerRunsPolicy(向后兼容)
        DynamicThreadPool pool = new DynamicThreadPool(1, 1, 1);
        try {
            String callerThreadName = Thread.currentThread().getName();
            CountDownLatch blockFirst = new CountDownLatch(1);
            CountDownLatch firstStarted = new CountDownLatch(1);
            pool.asExecutorService().submit(() -> {
                firstStarted.countDown();
                blockFirst.await();
                return null;
            });
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
            pool.asExecutorService().submit(() -> null);  // 进队列

            java.util.concurrent.atomic.AtomicReference<String> runnerName = new java.util.concurrent.atomic.AtomicReference<>();
            pool.asExecutorService().submit(() -> {
                runnerName.set(Thread.currentThread().getName());
                return null;
            });
            assertTrue("默认 CallerRunsPolicy 反压", callerThreadName.equals(runnerName.get()));
            blockFirst.countDown();
        } finally {
            pool.shutdown();
            pool.awaitTermination(2);
        }
    }

    @Test
    public void nullHandlerRejected() {
        try {
            new DynamicThreadPool(1, 1, 1, null);
            fail("null handler 应抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // 期望
        }
    }
}
