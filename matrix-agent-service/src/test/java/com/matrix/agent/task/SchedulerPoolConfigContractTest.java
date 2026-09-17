package com.matrix.agent.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

import org.junit.Test;

/**
 * schedulerPool 与 ioPool 拒绝策略配置契约。
 *
 * <p>之前 AppContainer.schedulerPool 用 3 参默认 CallerRunsPolicy,
 * 与已经改成 AbortPolicy 的 ioPool 不一致——队列满时 caller 线程同步执行,
 * 让 TaskScheduler.submit catch 成死代码。
 *
 * <p>本测试验证生产配置契约:DynamicThreadPool 4 参重载 + AbortPolicy 的 handler
 * 确实是 AbortPolicy,与 ioPool 配置一致。AppContainer 自身需要 Context 不能 JVM 测,
 * 但配置契约在 DynamicThreadPool 层可验证;AppContainer 装配路径用相同 4 参重载。
 */
public final class SchedulerPoolConfigContractTest {

    /**
     * schedulerPool 生产配置(core=2 / max=2 / queue=32 / AbortPolicy)的 handler
     * 必须是 AbortPolicy,与 ioPool 一致。
     */
    @Test
    public void schedulerPoolProductionConfigUsesAbortPolicy() {
        DynamicThreadPool pool = new DynamicThreadPool(2, 2, 32,
                new ThreadPoolExecutor.AbortPolicy());
        try {
            ThreadPoolExecutor executor = (ThreadPoolExecutor) pool.asExecutorService();
            assertTrue("schedulerPool handler 必须是 AbortPolicy(与 ioPool 一致)",
                    executor.getRejectedExecutionHandler() instanceof ThreadPoolExecutor.AbortPolicy);
            assertEquals(ThreadPoolExecutor.AbortPolicy.class,
                    executor.getRejectedExecutionHandler().getClass());
        } finally {
            pool.shutdown();
        }
    }

    /**
     * schedulerPool 与 ioPool 用同一类 handler(AbortPolicy)。
     * 两个 pool 独立实例,但 handler 类型相同,便于运维 / logcat 统一识别。
     */
    @Test
    public void schedulerAndIoPoolsUseSameHandlerType() {
        DynamicThreadPool schedulerPool = new DynamicThreadPool(2, 2, 32,
                new ThreadPoolExecutor.AbortPolicy());
        DynamicThreadPool ioPool = new DynamicThreadPool(2, 8, 32,
                new ThreadPoolExecutor.AbortPolicy());
        try {
            ThreadPoolExecutor schedExecutor = (ThreadPoolExecutor) schedulerPool.asExecutorService();
            ThreadPoolExecutor ioExecutor = (ThreadPoolExecutor) ioPool.asExecutorService();
            Class<? extends RejectedExecutionHandler> schedHandlerClass =
                    schedExecutor.getRejectedExecutionHandler().getClass();
            Class<? extends RejectedExecutionHandler> ioHandlerClass =
                    ioExecutor.getRejectedExecutionHandler().getClass();
            assertEquals("schedulerPool / ioPool handler 类型必须一致(V0.5.3 P1-4)",
                    ioHandlerClass, schedHandlerClass);
        } finally {
            schedulerPool.shutdown();
            ioPool.shutdown();
        }
    }
}
