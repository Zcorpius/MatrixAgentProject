package com.matrix.agent.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.host.MatrixExecutorRegistry;

import java.util.concurrent.ThreadPoolExecutor;

import org.junit.Test;

/**
 * Host task/network 执行器拒绝策略配置契约。
 *
 * <p>验证真实的 {@link MatrixExecutorRegistry}，而不是仅验证已移至 test source 的旧
 * DynamicThreadPool 测试夹具。task/network 两条生产 lane 都必须在饱和时显式拒绝，不能由
 * Binder 或 scheduler 调用线程同步执行。
 */
public final class SchedulerPoolConfigContractTest {

    /**
     * task lane 生产配置(core=2 / queue=32 / AbortPolicy)必须是真实 AbortPolicy。
     */
    @Test
    public void taskExecutorProductionConfigUsesAbortPolicy() {
        MatrixExecutorRegistry registry = new MatrixExecutorRegistry();
        try {
            ThreadPoolExecutor executor = (ThreadPoolExecutor) registry.taskExecutor();
            assertTrue("task executor handler 必须是 AbortPolicy",
                    executor.getRejectedExecutionHandler() instanceof ThreadPoolExecutor.AbortPolicy);
            assertEquals(ThreadPoolExecutor.AbortPolicy.class,
                    executor.getRejectedExecutionHandler().getClass());
        } finally {
            registry.shutdown();
        }
    }

    /**
     * task 与 network 生产 lane 使用同一类 handler(AbortPolicy)，便于统一将过载映射为
     * 可观察的业务失败。
     */
    @Test
    public void taskAndNetworkExecutorsUseSameHandlerType() {
        MatrixExecutorRegistry registry = new MatrixExecutorRegistry();
        try {
            ThreadPoolExecutor task = (ThreadPoolExecutor) registry.taskExecutor();
            ThreadPoolExecutor network = (ThreadPoolExecutor) registry.networkExecutor();
            assertEquals("task/network handler 类型必须一致",
                    network.getRejectedExecutionHandler().getClass(),
                    task.getRejectedExecutionHandler().getClass());
        } finally {
            registry.shutdown();
        }
    }
}
