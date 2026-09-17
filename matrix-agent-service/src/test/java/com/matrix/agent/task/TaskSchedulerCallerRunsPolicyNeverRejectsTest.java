package com.matrix.agent.task;

import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.matrix.agent.task.identity.Actor;
import com.matrix.agent.task.identity.AgentRequest;
import com.matrix.agent.task.identity.VehicleZone;
import com.matrix.agent.data.session.SessionLockManager;

/**
 * CallerRunsPolicy 配置下,schedulerPool 队列满时不抛 RejectedExecutionException,
 * 而是让 caller 线程同步执行任务——证明此前(默认 3 参 CallerRunsPolicy)
 * TaskScheduler.submit 的 catch (RejectedExecutionException) 是死代码。
 *
 * <p>对比测试:同配置(core=1/max=1/queue=1),CallerRunsPolicy 永远不进 REJECTED 路径;
 * AbortPolicy 进 REJECTED 路径(见 {@link TaskSchedulerAbortPolicyQueueFullRejectedTest})。
 */
public final class TaskSchedulerCallerRunsPolicyNeverRejectsTest {

    /**
     * core=1 / max=1 / queue=1 + CallerRunsPolicy(默认):任务 1 占 core,任务 2 进 queue,
     * 任务 3 队列已满 → CallerRunsPolicy 让 caller 线程同步执行,**不抛 RejectedExecutionException**。
     * 任务 3 的 runner 在 caller 线程执行,返回 SUCCEEDED 而非 REJECTED。
     */
    @Test
    public void callerRunsPolicyNeverReturnsRejectedOutcome() throws Exception {
        DynamicThreadPool pool = new DynamicThreadPool(1, 1, 1,
                new ThreadPoolExecutor.CallerRunsPolicy());
        try {
            TaskScheduler scheduler = new TaskScheduler(2, new SessionLockManager(),
                    pool.asExecutorService());

            CountDownLatch release1 = new CountDownLatch(1);
            CountDownLatch started1 = new CountDownLatch(1);
            CountDownLatch release2 = new CountDownLatch(1);
            AtomicInteger callerThreadRuns = new AtomicInteger(0);

            AgentRequest r1 = AgentRequest.builder("block-1", Actor.DRIVER)
                    .occupantZone(VehicleZone.DRIVER).build();
            AgentRequest r2 = AgentRequest.builder("block-2", Actor.DRIVER)
                    .occupantZone(VehicleZone.DRIVER).build();
            AgentRequest r3 = AgentRequest.builder("caller-runs", Actor.DRIVER)
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

                // 任务 3:CallerRunsPolicy 会在 caller 线程同步执行,不抛、不进 REJECTED 路径
                String callerThreadName = Thread.currentThread().getName();
                Future<AgentOutcome> f3 = scheduler.submit(r3, req -> {
                    // runner 在 caller 线程执行(CallerRunsPolicy 把它当前线程跑)
                    if (Thread.currentThread().getName().equals(callerThreadName)) {
                        callerThreadRuns.incrementAndGet();
                    }
                    return simpleOutcome(req);
                });

                AgentOutcome outcome = f3.get(5, TimeUnit.SECONDS);
                assertTrue("CallerRunsPolicy 路径下 outcome 不应是 REJECTED,actual="
                        + outcome.getFinalState(),
                        outcome.getFinalState() != TaskState.REJECTED);
                assertTrue("任务 3 应在 caller 线程同步执行(CallerRunsPolicy 标志)",
                        callerThreadRuns.get() >= 1);

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
