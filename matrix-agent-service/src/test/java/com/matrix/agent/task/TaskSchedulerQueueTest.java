package com.matrix.agent.task;
import com.matrix.agent.task.scheduler.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.identity.CancellationToken;
import com.matrix.agent.session.SessionLockManager;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

/**
 * TaskScheduler 排队追踪回归——覆盖旧实现 putIfAbsent 语义
 * 导致"排队中 task 不进 runningTasks"的缺陷。
 *
 * <p>4 个验证点:
 * <ol>
 *   <li>同 session 连续 submit 多个 task,runningCount 反映全部(含排队中)</li>
 *   <li>主驾抢占命中排队中的副驾(不必等副驾进 runner)</li>
 *   <li>task 完成后从队列精确移除</li>
 *   <li>不同 session 互不影响</li>
 * </ol>
 */
public final class TaskSchedulerQueueTest {

    /** 同 session 连续 submit 3 个,即使第 1 个还在跑、第 2/3 个在 session lock 排队,runningCount==3。 */
    @Test
    public void queuedTasksAreAllTracked() throws Exception {
        TaskScheduler scheduler = new TaskScheduler(2, new SessionLockManager());
        try {
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch firstStarted = new CountDownLatch(1);
            TaskScheduler.TaskRunner blockingRunner = req -> {
                if (firstStarted.getCount() > 0) firstStarted.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return makeOutcome(req, StopReason.NO_TOOL_CALL, TaskState.SUCCEEDED);
            };

            scheduler.submit(AgentRequest.builder("first", Actor.DRIVER)
                    .sessionId("queue-A").build(), blockingRunner);
            assertTrue("第 1 个 task 应启动", firstStarted.await(2, TimeUnit.SECONDS));

            scheduler.submit(AgentRequest.builder("second", Actor.DRIVER)
                    .sessionId("queue-A").build(), blockingRunner);
            scheduler.submit(AgentRequest.builder("third", Actor.DRIVER)
                    .sessionId("queue-A").build(), blockingRunner);

            // 给 worker 时间尝试获取 session lock(会阻塞,但 task 已记录到 deque)
            Thread.sleep(150);
            assertEquals("3 个 task 应都进 runningTasks(含排队)",
                    3, scheduler.runningCount());

            release.countDown();
            Thread.sleep(300);
            assertEquals("完成后队列应清空", 0, scheduler.runningCount());
        } finally {
            scheduler.shutdown();
        }
    }

    /**
     * 副驾 task 还在 workers 队列没进 runner,主驾立即 submit,
     * 抢占仍能命中副驾(不必等副驾真正执行)。
     */
    @Test
    public void preemptTargetsQueuedPassenger() throws Exception {
        // parallelism=1:唯一 worker 先跑长 task,后续 task 排队不进 runner
        TaskScheduler scheduler = new TaskScheduler(1, new SessionLockManager());
        try {
            CountDownLatch blockerStarted = new CountDownLatch(1);
            CountDownLatch releaseBlocker = new CountDownLatch(1);
            // 不同 session 的阻塞 task,占住唯一 worker
            scheduler.submit(AgentRequest.builder("blocker", Actor.DRIVER)
                            .sessionId("other-session").build(),
                    req -> {
                        blockerStarted.countDown();
                        try {
                            releaseBlocker.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                        return makeOutcome(req, StopReason.NO_TOOL_CALL, TaskState.SUCCEEDED);
                    });
            assertTrue("blocker 应启动", blockerStarted.await(2, TimeUnit.SECONDS));

            CancellationToken passengerToken = new CancellationToken();
            // 副驾只读 task 进 workers 队列但未跑
            scheduler.submit(AgentRequest.builder("查电量", Actor.PASSENGER)
                    .sessionId("preempt-queued")
                    .readOnlyHint(true)
                    .cancellationToken(passengerToken)
                    .build(),
                    req -> makeOutcome(req, StopReason.NO_TOOL_CALL, TaskState.SUCCEEDED));

            Thread.sleep(100);
            assertEquals("副驾 token 不应被 cancel(主驾还没提交)",
                    false, passengerToken.isCancelled());

            // 主驾提交,应触发抢占——即使副驾 task 还在 workers 队列
            scheduler.submit(AgentRequest.builder("查胎压", Actor.DRIVER)
                    .sessionId("preempt-queued")
                    .readOnlyHint(true)
                    .build(),
                    req -> makeOutcome(req, StopReason.NO_TOOL_CALL, TaskState.SUCCEEDED));

            assertTrue("副驾 token 应被 cancel(即使在排队中)", awaitCancelled(passengerToken, 2_000));
            releaseBlocker.countDown();
        } finally {
            scheduler.shutdown();
        }
    }

    /** 3 个 task 完成 2 个,runningCount 应准确为 1(精确移除,不是整个 deque 清掉)。 */
    @Test
    public void taskCompletionRemovesFromQueue() throws Exception {
        TaskScheduler scheduler = new TaskScheduler(2, new SessionLockManager());
        try {
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch firstStarted = new CountDownLatch(1);
            TaskScheduler.TaskRunner blockingRunner = req -> {
                if (firstStarted.getCount() > 0) firstStarted.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return makeOutcome(req, StopReason.NO_TOOL_CALL, TaskState.SUCCEEDED);
            };

            Future<AgentOutcome> f1 = scheduler.submit(AgentRequest.builder("a", Actor.DRIVER)
                    .sessionId("removal-C").build(), blockingRunner);
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            Future<AgentOutcome> f2 = scheduler.submit(AgentRequest.builder("b", Actor.DRIVER)
                    .sessionId("removal-C").build(), blockingRunner);
            Future<AgentOutcome> f3 = scheduler.submit(AgentRequest.builder("c", Actor.DRIVER)
                    .sessionId("removal-C").build(), blockingRunner);

            Thread.sleep(150);
            assertEquals(3, scheduler.runningCount());

            release.countDown();
            f1.get(5, TimeUnit.SECONDS);
            f2.get(5, TimeUnit.SECONDS);
            // f3 可能还在 session lock 队列,等它也跑完
            Thread.sleep(200);
            f3.get(5, TimeUnit.SECONDS);
            assertEquals("全部完成后队列应清空", 0, scheduler.runningCount());
        } finally {
            scheduler.shutdown();
        }
    }

    /** session-A 与 session-B 互不影响,各自队列独立维护。 */
    @Test
    public void differentSessionsDoNotInterfere() throws Exception {
        TaskScheduler scheduler = new TaskScheduler(4, new SessionLockManager());
        try {
            CountDownLatch releaseA = new CountDownLatch(1);
            CountDownLatch releaseB = new CountDownLatch(1);
            CountDownLatch aStarted = new CountDownLatch(1);
            CountDownLatch bStarted = new CountDownLatch(1);

            scheduler.submit(AgentRequest.builder("a1", Actor.DRIVER).sessionId("S-A").build(),
                    req -> {
                        aStarted.countDown();
                        try { releaseA.await(5, TimeUnit.SECONDS); }
                        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                        return makeOutcome(req, StopReason.NO_TOOL_CALL, TaskState.SUCCEEDED);
                    });
            scheduler.submit(AgentRequest.builder("a2", Actor.DRIVER).sessionId("S-A").build(),
                    req -> {
                        try { releaseA.await(5, TimeUnit.SECONDS); }
                        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                        return makeOutcome(req, StopReason.NO_TOOL_CALL, TaskState.SUCCEEDED);
                    });
            scheduler.submit(AgentRequest.builder("a3", Actor.DRIVER).sessionId("S-A").build(),
                    req -> makeOutcome(req, StopReason.NO_TOOL_CALL, TaskState.SUCCEEDED));

            scheduler.submit(AgentRequest.builder("b1", Actor.PASSENGER).sessionId("S-B").build(),
                    req -> {
                        bStarted.countDown();
                        try { releaseB.await(5, TimeUnit.SECONDS); }
                        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                        return makeOutcome(req, StopReason.NO_TOOL_CALL, TaskState.SUCCEEDED);
                    });
            scheduler.submit(AgentRequest.builder("b2", Actor.PASSENGER).sessionId("S-B").build(),
                    req -> makeOutcome(req, StopReason.NO_TOOL_CALL, TaskState.SUCCEEDED));

            assertTrue(aStarted.await(2, TimeUnit.SECONDS));
            assertTrue(bStarted.await(2, TimeUnit.SECONDS));
            Thread.sleep(150);
            assertEquals("session-A 3 + session-B 2 = 5", 5, scheduler.runningCount());

            releaseA.countDown();
            releaseB.countDown();
            Thread.sleep(400);
            assertEquals("全部完成后队列应清空", 0, scheduler.runningCount());
        } finally {
            scheduler.shutdown();
        }
    }

    private static AgentOutcome makeOutcome(AgentRequest request, StopReason reason, TaskState state) {
        Trajectory trajectory = new Trajectory();
        trajectory.finish(reason, 0L, 0);
        return new AgentOutcome(request.getRequestId(), state, reason, trajectory, 0L);
    }

    private static boolean awaitCancelled(CancellationToken token, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (token.isCancelled()) return true;
            Thread.sleep(10);
        }
        return token.isCancelled();
    }
}
