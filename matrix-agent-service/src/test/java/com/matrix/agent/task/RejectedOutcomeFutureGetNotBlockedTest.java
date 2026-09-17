package com.matrix.agent.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.matrix.agent.task.identity.Actor;
import com.matrix.agent.task.identity.AgentRequest;
import com.matrix.agent.task.identity.VehicleZone;
import com.matrix.agent.data.session.SessionLockManager;

/**
 * AbortPolicy 触发的 REJECTED future 不应阻塞后续任务的提交与执行。
 *
 * <p>验证场景:任务 3 被 REJECTED 后,释放任务 1/2 的阻塞,任务 1/2 仍能正常完成——
 * 证明 REJECTED 路径不污染 pool 状态(runningTasks map 清理正确,workers 池没被卡)。
 * 这是加 catch 块时承诺的"REJECTED 不留遗害"行为,本测试在
 * 真实队列满路径下回归。
 */
public final class RejectedOutcomeFutureGetNotBlockedTest {

    @Test
    public void rejectedTaskDoesNotBlockSubsequentTasks() throws Exception {
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
                assertTrue(started1.await(2, TimeUnit.SECONDS));

                Future<AgentOutcome> f2 = scheduler.submit(r2, req -> {
                    awaitUnchecked(release2);
                    return simpleOutcome(req);
                });
                Thread.sleep(80);

                Future<AgentOutcome> f3 = scheduler.submit(r3, req -> {
                    throw new AssertionError("runner 不该被调用(已 REJECTED)");
                });

                AgentOutcome rejected = f3.get(5, TimeUnit.SECONDS);
                assertEquals(TaskState.REJECTED, rejected.getFinalState());

                // 关键:释放阻塞后,任务 1 和 2 必须能正常完成,REJECTED 不留遗害
                release1.countDown();
                release2.countDown();
                AgentOutcome o1 = f1.get(5, TimeUnit.SECONDS);
                AgentOutcome o2 = f2.get(5, TimeUnit.SECONDS);
                assertEquals("任务 1 仍能正常完成", TaskState.SUCCEEDED, o1.getFinalState());
                assertEquals("任务 2 仍能正常完成", TaskState.SUCCEEDED, o2.getFinalState());
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
