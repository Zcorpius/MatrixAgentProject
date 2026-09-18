package com.matrix.agent.task;
import com.matrix.agent.task.scheduler.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.identity.VehicleZone;
import com.matrix.agent.session.SessionLockManager;

/**
 * AbortPolicy 配置下,schedulerPool 队列满(非 shutdown)→ RejectedExecutionException
 * → TaskScheduler.submit 的 catch 块触发 → 返回 REJECTED 终态 outcome(future.get() 立即返回)。
 *
 * <p>与 {@link TaskSchedulerRejectedExecutionTest}(用 pool.shutdown() 触发)互补:本测试验证
 * 真实"队列满 + AbortPolicy" 路径,这是新增的 catch 块在生产配置下第一次真正生效。
 * 此前 schedulerPool 用 CallerRunsPolicy(默认 3 参),队列满时不抛而是 caller 线程同步执行,
 * 让这段 catch 成死代码。
 */
public final class TaskSchedulerAbortPolicyQueueFullRejectedTest {

    /**
     * core=1 / max=1 / queue=1 + AbortPolicy:任务 1 占 core 线程,任务 2 进 queue,
     * 任务 3 队列已满 + max 不能扩 → AbortPolicy 抛 RejectedExecutionException → catch 转 REJECTED outcome。
     */
    @Test
    public void abortPolicyQueueFullTriggersRejectedOutcome() throws Exception {
        DynamicThreadPool pool = new DynamicThreadPool(1, 1, 1,
                new ThreadPoolExecutor.AbortPolicy());
        try {
            TaskScheduler scheduler = new TaskScheduler(2, new SessionLockManager(),
                    pool.asExecutorService());

            CountDownLatch release1 = new CountDownLatch(1);
            CountDownLatch started1 = new CountDownLatch(1);
            CountDownLatch release2 = new CountDownLatch(1);

            AgentRequest r1 = AgentRequest.builder("block-1", Actor.DRIVER)
                    .occupantZone(VehicleZone.DRIVER).build();
            AgentRequest r2 = AgentRequest.builder("block-2", Actor.DRIVER)
                    .occupantZone(VehicleZone.DRIVER).build();
            AgentRequest r3 = AgentRequest.builder("rejected", Actor.DRIVER)
                    .occupantZone(VehicleZone.DRIVER).build();

            Future<AgentOutcome> f1 = scheduler.submit(r1, req -> {
                started1.countDown();
                awaitUnchecked(release1);
                return simpleOutcome(req);
            });
            try {
                assertTrue("任务 1 必须进入 core 线程", started1.await(2, TimeUnit.SECONDS));

                Future<AgentOutcome> f2 = scheduler.submit(r2, req -> {
                    awaitUnchecked(release2);
                    return simpleOutcome(req);
                });
                Thread.sleep(80);  // 让 r2 进 queue

                long startMs = System.currentTimeMillis();
                Future<AgentOutcome> f3 = scheduler.submit(r3, req -> {
                    throw new AssertionError("runner 不该被调用(已 REJECTED)");
                });

                AgentOutcome outcome = f3.get(5, TimeUnit.SECONDS);
                long elapsed = System.currentTimeMillis() - startMs;

                assertNotNull("rejected future 非空", f3);
                assertEquals(TaskState.REJECTED, outcome.getFinalState());
                assertEquals(StopReason.REJECTED, outcome.getStopReason());
                assertTrue("rejected future.get() 必须立即返回(< 500ms),elapsed=" + elapsed,
                        elapsed < 500L);

                release1.countDown();
                release2.countDown();
                f1.get(5, TimeUnit.SECONDS);
                f2.get(5, TimeUnit.SECONDS);
            } finally {
                release1.countDown();
                release2.countDown();
            }
        } finally {
            pool.shutdown();
        }
    }

    private static AgentOutcome simpleOutcome(AgentRequest req) {
        return new AgentOutcome(req.getRequestId(), TaskState.SUCCEEDED, StopReason.DONE,
                new Trajectory(), 0L);
    }

    private static void awaitUnchecked(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
