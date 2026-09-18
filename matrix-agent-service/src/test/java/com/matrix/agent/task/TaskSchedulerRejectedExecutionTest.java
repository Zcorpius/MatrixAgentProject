package com.matrix.agent.task;
import com.matrix.agent.task.scheduler.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;

import org.junit.Test;

import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.identity.VehicleZone;
import com.matrix.agent.session.SessionLockManager;

/**
 * TaskScheduler 在 schedulerPool 队列满 / Executor 拒绝时的契约测试。
 *
 * <p>评审发现共享池嵌套等待死锁,改为两独立池 + 显式拒绝处理。TaskScheduler submit 抛
 * RejectedExecutionException 时,返回已完成 Future,值是 REJECTED terminalOutcome
 * (TaskState.REJECTED + StopReason.REJECTED)。Repository 兜底 audit 自动覆盖。
 */
public final class TaskSchedulerRejectedExecutionTest {

    @Test
    public void rejectedSubmitReturnsRejectedOutcome() throws Exception {
        // 已 shutdown 的 pool——任何 submit 都会抛 RejectedExecutionException
        DynamicThreadPool pool = new DynamicThreadPool(1, 1, 1,
                new ThreadPoolExecutor.AbortPolicy());
        pool.shutdown();
        TaskScheduler scheduler = new TaskScheduler(2, new SessionLockManager(),
                pool.asExecutorService());

        AgentRequest request = AgentRequest.builder("test", Actor.DRIVER)
                .occupantZone(VehicleZone.DRIVER)
                .build();

        Future<AgentOutcome> future = scheduler.submit(request, req -> {
            throw new AssertionError("runner 不该被调用(已 REJECTED)");
        });

        assertNotNull("即使 reject,future 必须返回非 null", future);
        AgentOutcome outcome = future.get();
        assertEquals(TaskState.REJECTED, outcome.getFinalState());
        assertEquals(StopReason.REJECTED, outcome.getStopReason());
    }

    @Test
    public void rejectedFutureGetReturnsImmediately() throws Exception {
        // 已 rejected 的 future.get() 不阻塞,直接返回 outcome
        DynamicThreadPool pool = new DynamicThreadPool(1, 1, 1,
                new ThreadPoolExecutor.AbortPolicy());
        pool.shutdown();
        TaskScheduler scheduler = new TaskScheduler(2, new SessionLockManager(),
                pool.asExecutorService());

        AgentRequest request = AgentRequest.builder("test2", Actor.DRIVER)
                .occupantZone(VehicleZone.DRIVER)
                .build();

        Future<AgentOutcome> future = scheduler.submit(request, req -> {
            throw new AssertionError("unreachable");
        });
        long start = System.currentTimeMillis();
        AgentOutcome outcome = future.get();
        long elapsed = System.currentTimeMillis() - start;
        assertEquals(TaskState.REJECTED, outcome.getFinalState());
        assertEquals(StopReason.REJECTED, outcome.getStopReason());
        // get() 应立即返回(< 200ms),不阻塞等待 runner
        assertEquals("rejected future.get() 必须立即返回", true, elapsed < 200L);
    }
}
