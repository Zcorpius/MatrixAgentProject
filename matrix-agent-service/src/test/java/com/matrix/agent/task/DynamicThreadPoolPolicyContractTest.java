package com.matrix.agent.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.ThreadPoolExecutor;

import org.junit.Test;

/**
 * DynamicThreadPool 双构造器拒绝策略契约。
 *
 * <p>评审发现 AppContainer.ioPool 用默认 3 参构造器(CallerRunsPolicy),队列满时会在
 * caller 线程(TaskScheduler 线程)同步执行模型 HTTP 调用,绕过 future.get(timeout) + abort hook。
 *
 * <p>本测试验证:
 * <ul>
 *   <li>3 参默认构造器 → CallerRunsPolicy(schedulerPool 用,AgentEngine.execute 顶层任务不可丢);</li>
 *   <li>4 参重载指定 AbortPolicy → AbortPolicy(ioPool 用,显式拒绝 + 上层 catch 转 POLICY_HALT)。</li>
 * </ul>
 */
public final class DynamicThreadPoolPolicyContractTest {

    @Test
    public void defaultConstructorUsesCallerRunsPolicy() {
        DynamicThreadPool pool = new DynamicThreadPool(1, 1, 1);
        ThreadPoolExecutor executor = (ThreadPoolExecutor) pool.asExecutorService();
        assertTrue("3 参默认 = CallerRunsPolicy",
                executor.getRejectedExecutionHandler() instanceof ThreadPoolExecutor.CallerRunsPolicy);
        pool.shutdown();
    }

    @Test
    public void abortPolicyConstructorUsesAbortPolicy() {
        DynamicThreadPool pool = new DynamicThreadPool(1, 1, 1,
                new ThreadPoolExecutor.AbortPolicy());
        ThreadPoolExecutor executor = (ThreadPoolExecutor) pool.asExecutorService();
        assertTrue("4 参 AbortPolicy = AbortPolicy",
                executor.getRejectedExecutionHandler() instanceof ThreadPoolExecutor.AbortPolicy);
        // 显式类型校验——避免子类继承干扰
        assertEquals(ThreadPoolExecutor.AbortPolicy.class,
                executor.getRejectedExecutionHandler().getClass());
        pool.shutdown();
    }

    @Test
    public void discardPolicyConstructorUsesDiscardPolicy() {
        DynamicThreadPool pool = new DynamicThreadPool(1, 1, 1,
                new ThreadPoolExecutor.DiscardPolicy());
        ThreadPoolExecutor executor = (ThreadPoolExecutor) pool.asExecutorService();
        assertTrue("4 参 DiscardPolicy = DiscardPolicy",
                executor.getRejectedExecutionHandler() instanceof ThreadPoolExecutor.DiscardPolicy);
        pool.shutdown();
    }

    @Test
    public void rejectPolicyIsConfigurableAfterConstruction() {
        // 不同 handler 实例不应共享——每个 pool 持有自己的 handler
        DynamicThreadPool p1 = new DynamicThreadPool(1, 1, 1,
                new ThreadPoolExecutor.AbortPolicy());
        DynamicThreadPool p2 = new DynamicThreadPool(1, 1, 1,
                new ThreadPoolExecutor.CallerRunsPolicy());
        ThreadPoolExecutor e1 = (ThreadPoolExecutor) p1.asExecutorService();
        ThreadPoolExecutor e2 = (ThreadPoolExecutor) p2.asExecutorService();
        assertTrue(e1.getRejectedExecutionHandler() instanceof ThreadPoolExecutor.AbortPolicy);
        assertTrue(e2.getRejectedExecutionHandler() instanceof ThreadPoolExecutor.CallerRunsPolicy);
        assertTrue("两个 pool 的 handler 必须不同",
                e1.getRejectedExecutionHandler() != e2.getRejectedExecutionHandler());
        p1.shutdown();
        p2.shutdown();
    }
}
