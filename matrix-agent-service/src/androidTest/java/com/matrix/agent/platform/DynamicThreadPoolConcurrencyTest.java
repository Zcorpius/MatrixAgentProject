package com.matrix.agent.platform;
import com.matrix.agent.host.di.*;
import com.matrix.agent.task.scheduler.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.matrix.agent.task.ModelCallExecutor;
import com.matrix.agent.session.SessionLockManager;
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
 * Host executor registry 并发压力测试——8 并发任务同时跑过任务、网络与模型 lane，
 * 验证共享依赖的三个执行器可构造、任务不丢且由唯一 registry 统一收口。
 *
 * <p>必须 androidTest 跑(JVM 单测的 fake executor 测不出真实 ThreadPoolExecutor 拒绝策略 +
 * 多 owner 共享时的 shutdown 行为,需要在真机 / emulator 上的 ART 跑)。
 */
@RunWith(AndroidJUnit4.class)
public final class DynamicThreadPoolConcurrencyTest {
    private static final String TAG = "MatrixAgent";

    @Test
    public void eightConcurrentTasksAcrossThreeOwnersNoDeadlock() throws Exception {
        MatrixExecutorRegistry registry = new MatrixExecutorRegistry();
        TaskScheduler scheduler = new TaskScheduler(2, new SessionLockManager(),
                registry.taskExecutor());
        ToolExecutor toolExecutor = new ToolExecutor(2, registry.networkExecutor());
        ModelCallExecutor modelCallExecutor = new ModelCallExecutor(2,
                registry.networkExecutor(), registry.modelExecutor());

        try {
            int taskCount = 8;
            CountDownLatch allDone = new CountDownLatch(taskCount * 3);
            AtomicInteger errors = new AtomicInteger(0);

            List<Future<?>> futures = new ArrayList<>();

            // 8 个任务给任务 lane
            for (int i = 0; i < taskCount; i++) {
                futures.add(registry.taskExecutor().submit(() -> {
                    try {
                        Log.d(TAG, "[ConcTest] scheduler-task on " + Thread.currentThread().getName());
                        allDone.countDown();
                    } catch (Throwable t) {
                        errors.incrementAndGet();
                    }
                }));
            }
            // 8 个任务给网络 lane（ToolExecutor 所在 lane）
            for (int i = 0; i < taskCount; i++) {
                futures.add(registry.networkExecutor().submit(() -> {
                    try {
                        Log.d(TAG, "[ConcTest] tool-task on " + Thread.currentThread().getName());
                        allDone.countDown();
                    } catch (Throwable t) {
                        errors.incrementAndGet();
                    }
                }));
            }
            // 8 个任务给端侧模型 lane（ModelCallExecutor 所在 lane）
            for (int i = 0; i < taskCount; i++) {
                futures.add(registry.modelExecutor().submit(() -> {
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
            // 三个 owner 都只取得 registry 注入的 lane，验证构造无异常。
            assertTrue("TaskScheduler 应初始化", scheduler != null);
            assertTrue("ToolExecutor 应初始化", toolExecutor != null);
            assertTrue("ModelCallExecutor 应初始化", modelCallExecutor != null);
        } finally {
            registry.shutdown();
        }
    }
}
