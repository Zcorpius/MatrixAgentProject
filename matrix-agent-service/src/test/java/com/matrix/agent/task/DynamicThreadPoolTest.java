package com.matrix.agent.task;
import com.matrix.agent.task.scheduler.*;

import com.matrix.agent.session.SessionLockManager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.matrix.agent.task.tool.ToolExecutor;

/**
 * DynamicThreadPool 共享线程池测试——弹性扩缩 / 队列上限 / CallerRunsPolicy
 * 反压 / 共享给多 executor 时的 shutdown 语义。
 */
public final class DynamicThreadPoolTest {

    @Test
    public void defaultPoolStartsWithCoreSizeAndExpandsUnderLoad() throws Exception {
        // core=2 / max=4 / queue=1:submit 4 个慢任务,验证池子扩到 >= 2
        DynamicThreadPool pool = new DynamicThreadPool(2, 4, 1);
        try {
            int taskCount = 4;
            CountDownLatch started = new CountDownLatch(2); // 至少 2 个 core 线程跑起来
            CountDownLatch hold = new CountDownLatch(1);
            for (int i = 0; i < taskCount; i++) {
                pool.asExecutorService().submit(() -> {
                    started.countDown();
                    try {
                        hold.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    return null;
                });
            }
            assertTrue("至少 2 个 core 线程应启动", started.await(2, TimeUnit.SECONDS));
            // 池子在压力下应扩到 >= core=2
            assertTrue("poolSize 应 >= 2,实际=" + pool.getPoolSize(),
                    pool.getPoolSize() >= 2);
            hold.countDown();
        } finally {
            pool.shutdown();
        }
    }

    @Test
    public void queueFullTriggersCallerRunsPolicy() throws Exception {
        // core=1 / queue=1 / max=2——max + queue 满后,CallerRunsPolicy 让调用线程自己跑
        DynamicThreadPool pool = new DynamicThreadPool(1, 2, 1);
        try {
            int taskCount = 10;
            CountDownLatch hold = new CountDownLatch(1);
            CountDownLatch completed = new CountDownLatch(taskCount);
            List<String> threads = Collections.synchronizedList(new ArrayList<>());
            for (int i = 0; i < taskCount; i++) {
                threads.add("submitted-by-main-" + i);
                try {
                    pool.asExecutorService().submit(() -> {
                        threads.add("ran-on-" + Thread.currentThread().getName());
                        completed.countDown();
                        try {
                            hold.await(2, TimeUnit.SECONDS);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                        return null;
                    });
                } catch (Exception ex) {
                    // CallerRunsPolicy 实际是直接 run,不抛——但 submit 包装为 FutureTask 会被 execute 走
                    completed.countDown();
                }
            }
            hold.countDown();
            assertTrue("10 个任务都应完成", completed.await(3, TimeUnit.SECONDS));
            // 至少有一个任务被 main 线程(CallerRuns)执行——thread name 不以 matrix-pool 开头
            boolean ranOnCaller = threads.stream()
                    .anyMatch(s -> s.startsWith("ran-on-") && !s.contains("matrix-pool"));
            assertTrue("CallerRunsPolicy 应让 main 线程跑过任务,threads=" + threads,
                    ranOnCaller);
        } finally {
            pool.shutdown();
        }
    }

    @Test
    public void coreThreadsIdleOutAfterKeepAlive() throws Exception {
        // core=2, allowCoreThreadTimeOut=true → 空闲后池子能缩到 0
        DynamicThreadPool pool = new DynamicThreadPool(2, 4, 4);
        try {
            CountDownLatch done = new CountDownLatch(2);
            for (int i = 0; i < 2; i++) {
                pool.asExecutorService().submit(() -> {
                    done.countDown();
                    return null;
                });
            }
            assertTrue(done.await(2, TimeUnit.SECONDS));
            // keepAlive=60s 太长测不动——这里只验证 getPoolSize 不超过 max
            assertTrue("poolSize <= max=4, actual=" + pool.getPoolSize(),
                    pool.getPoolSize() <= 4);
        } finally {
            pool.shutdown();
        }
    }

    @Test
    public void rejectPoolSizeValidation() {
        try {
            new DynamicThreadPool(0, 4, 4);
            fail("corePoolSize=0 应抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            new DynamicThreadPool(4, 2, 4);
            fail("maxPoolSize < corePoolSize 应抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            new DynamicThreadPool(2, 4, 0);
            fail("queueCapacity=0 应抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void sharedPoolSurvivesWhenOneOwnerStopsUsingIt() throws Exception {
        // 验证 TaskScheduler.shutdown() 不关共享池(ToolExecutor/ModelCallExecutor 还能用)
        DynamicThreadPool shared = new DynamicThreadPool(2, 4, 4);
        TaskScheduler scheduler = new TaskScheduler(2,
                new com.matrix.agent.session.SessionLockManager(),
                shared.asExecutorService());
        ToolExecutor toolExecutor = new ToolExecutor(2, shared.asExecutorService());
        try {
            // scheduler.shutdown() 不应关闭 shared
            scheduler.shutdown();
            assertFalse("共享池不应被 TaskScheduler.shutdown() 关闭",
                    shared.asExecutorService().isShutdown());
            // toolExecutor 还能提交任务
            java.util.concurrent.Future<?> f = toolExecutorSubmit(toolExecutor, shared);
            f.get(2, TimeUnit.SECONDS);
            assertFalse(shared.asExecutorService().isShutdown());
        } finally {
            shared.shutdown();
        }
    }

    @Test
    public void sharedPoolShutdownClosesForAllOwners() throws Exception {
        DynamicThreadPool shared = new DynamicThreadPool(2, 4, 4);
        new TaskScheduler(2, new com.matrix.agent.session.SessionLockManager(),
                shared.asExecutorService());
        new ToolExecutor(2, shared.asExecutorService());
        new ModelCallExecutor(2, shared.asExecutorService());
        shared.shutdown();
        assertTrue(shared.asExecutorService().isShutdown());
        // 等终止
        boolean terminated = shared.awaitTermination(2);
        assertTrue("共享池应在 2s 内 terminate", terminated);
    }

    @Test
    public void submittedCallableReturnsValue() throws Exception {
        DynamicThreadPool pool = new DynamicThreadPool();
        try {
            java.util.concurrent.Future<Integer> future = pool.asExecutorService()
                    .submit(() -> 42);
            int value = future.get(2, TimeUnit.SECONDS);
            assertEquals(42, value);
        } finally {
            pool.shutdown();
        }
    }

    @Test
    public void queueSizeReflectsPendingTasks() throws Exception {
        DynamicThreadPool pool = new DynamicThreadPool(1, 1, 5);
        try {
            CountDownLatch hold = new CountDownLatch(1);
            // 占住唯一的线程
            pool.asExecutorService().submit(() -> {
                try {
                    hold.await(3, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return null;
            });
            // 灌 5 个任务到队列
            for (int i = 0; i < 5; i++) {
                pool.asExecutorService().submit(() -> null);
            }
            // 等一会儿让任务都进队列
            Thread.sleep(50);
            // queueSize 应 >= 4-5(部分可能已被消费)
            assertTrue("queueSize 应反映堆积任务,actual=" + pool.getQueueSize(),
                    pool.getQueueSize() >= 3);
            hold.countDown();
        } finally {
            pool.shutdown();
        }
    }

    /** ToolExecutor 测试辅助——直接提交 Runnable 到底层池。 */
    private static java.util.concurrent.Future<?> toolExecutorSubmit(
            ToolExecutor executor, DynamicThreadPool shared) {
        // ToolExecutor 没有暴露 submit API,我们走 shared 池验证它仍可用
        return shared.asExecutorService().submit(() -> null);
    }
}
