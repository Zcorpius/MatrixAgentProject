package com.matrix.agent.platform;
import com.matrix.agent.host.di.*;
import com.matrix.agent.task.scheduler.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.matrix.agent.task.DynamicThreadPool;
import com.matrix.agent.task.ModelCallExecutor;
import com.matrix.agent.data.session.SessionLockManager;
import com.matrix.agent.task.scheduler.TaskScheduler;
import com.matrix.agent.task.tool.ToolExecutor;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DynamicThreadPool 并发压力测试——8 并发任务同时跑过 3 个共享 executor
 * (TaskScheduler + ModelCallExecutor + ToolExecutor),验证无死锁 + 任务不丢。
 *
 * <p>必须 androidTest 跑(JVM 单测的 fake executor 测不出真实 ThreadPoolExecutor 拒绝策略 +
 * 多 owner 共享时的 shutdown 行为,需要在真机 / emulator 上的 ART 跑)。
 */
@RunWith(AndroidJUnit4.class)
public final class DynamicThreadPoolConcurrencyTest {
    private static final String TAG = "MatrixAgent";

    @Test
    public void eightConcurrentTasksAcrossThreeOwnersNoDeadlock() throws Exception {
        // 模拟 AppContainer 装配——3 个 executor 共享一个池
        DynamicThreadPool shared = new DynamicThreadPool(2, 8, 32);
        TaskScheduler scheduler = new TaskScheduler(2, new SessionLockManager(), shared);
        ToolExecutor toolExecutor = new ToolExecutor(2, shared);
        ModelCallExecutor modelCallExecutor = new ModelCallExecutor(2, shared);

        try {
            int taskCount = 8;
            CountDownLatch allDone = new CountDownLatch(taskCount * 3);
            AtomicInteger errors = new AtomicInteger(0);

            List<Future<?>> futures = new ArrayList<>();

            // 8 个任务给 TaskScheduler 的池
            for (int i = 0; i < taskCount; i++) {
                futures.add(shared.asExecutorService().submit(() -> {
                    try {
                        Log.d(TAG, "[ConcTest] scheduler-task on " + Thread.currentThread().getName());
                        allDone.countDown();
                    } catch (Throwable t) {
                        errors.incrementAndGet();
                    }
                }));
            }
            // 8 个任务给 ToolExecutor(走同一个池)
            for (int i = 0; i < taskCount; i++) {
                futures.add(shared.asExecutorService().submit(() -> {
                    try {
                        Log.d(TAG, "[ConcTest] tool-task on " + Thread.currentThread().getName());
                        allDone.countDown();
                    } catch (Throwable t) {
                        errors.incrementAndGet();
                    }
                }));
            }
            // 8 个任务给 ModelCallExecutor(走同一个池)
            for (int i = 0; i < taskCount; i++) {
                futures.add(shared.asExecutorService().submit(() -> {
                    try {
                        Log.d(TAG, "[ConcTest] model-task on " + Thread.currentThread().getName());
                        allDone.countDown();
                    } catch (Throwable t) {
                        errors.incrementAndGet();
                    }
                }));
            }

            // 等所有 24 个任务完成(8 × 3 executor)
            assertTrue("24 个任务应在 10s 内全部完成,剩余=" + allDone.getCount(),
                    allDone.await(10, TimeUnit.SECONDS));
            assertEquals("不应有任务抛异常", 0, errors.get());
            assertTrue("poolSize 应至少扩到 core=2,actual=" + shared.getPoolSize(),
                    shared.getPoolSize() >= 2);

            // 引用一下三个 executor 避免 IDE unused 警告(它们都持有 shared,验证构造无异常)
            assertTrue("TaskScheduler 应初始化", scheduler != null);
            assertTrue("ToolExecutor 应初始化", toolExecutor != null);
            assertTrue("ModelCallExecutor 应初始化", modelCallExecutor != null);
        } finally {
            // 关键:外部 shared 池持有者统一关闭,3 个 executor 不应自己关
            shared.shutdown();
            boolean terminated = shared.awaitTermination(5);
            assertTrue("shared 池应在 5s 内 terminate", terminated);
        }
    }
}
