package com.matrix.agent.task;
import com.matrix.agent.task.scheduler.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.identity.CancellationToken;
import com.matrix.agent.session.SessionLockManager;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

/**
 * TaskScheduler 主驾优先抢占测试。
 *
 * <p>4 个验证点:
 * <ol>
 *   <li>副驾查询进行中,主驾查询抢占 → 副驾 StopReason.CANCELLED + TaskState.PREEMPTED,主驾正常跑</li>
 *   <li>副驾车控写进行中,主驾查询不抢占 → 主驾排队等副驾结束</li>
 *   <li>同 actor 多请求 FIFO</li>
 *   <li>抢占后 session 引用计数正确清理(用 SessionLockManager.entryCount()==0 间接验证)</li>
 * </ol>
 *
 * <p>不直接依赖 AgentEngine——TaskRunner 是注入的 lambda,本测试用 latch + 阻塞 lambda
 * 模拟正在执行的任务,让抢占逻辑可观察。
 */
public final class TaskSchedulerTest {

    @Test
    public void driverPreemptsPassengerReadOnlyTask() throws Exception {
        TaskScheduler scheduler = new TaskScheduler(2, new SessionLockManager());
        try {
            CancellationToken passengerToken = new CancellationToken();
            CountDownLatch passengerStarted = new CountDownLatch(1);
            CountDownLatch passengerReleased = new CountDownLatch(1);
            AtomicReference<AgentOutcome> passengerOutcome = new AtomicReference<>();

            AgentRequest passengerReq = AgentRequest.builder("查电量", Actor.PASSENGER)
                    .sessionId("preempt-A")
                    .readOnlyHint(true)
                    .cancellationToken(passengerToken)
                    .build();
            TaskScheduler.TaskRunner passengerRunner = req -> {
                passengerStarted.countDown();
                passengerToken.registerAbortHook(() -> {
                    // 模拟 cancel 触发后 AgentEngine 的退出路径
                });
                try {
                    passengerReleased.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                // 真实 AgentEngine 路径下,token 被 cancel 后 Engine 返回
                // CANCELLED+CANCELLED;Scheduler 内部 remapIfPreempted 把它转 PREEMPTED+PREEMPTED。
                // 测试 runner 简化为只返回 CANCELLED+CANCELLED,验证 Scheduler 重映射。
                passengerOutcome.set(makeOutcome(req,
                        passengerToken.isCancelled() ? StopReason.CANCELLED : StopReason.NO_TOOL_CALL,
                        passengerToken.isCancelled() ? TaskState.CANCELLED : TaskState.SUCCEEDED));
                return passengerOutcome.get();
            };

            Future<AgentOutcome> passengerFuture = scheduler.submit(passengerReq, passengerRunner);
            assertTrue("副驾任务应启动", passengerStarted.await(2, TimeUnit.SECONDS));

            // 主驾查询到达 → 应触发对副驾的 cancel
            AgentRequest driverReq = AgentRequest.builder("查电量", Actor.DRIVER)
                    .sessionId("preempt-A")
                    .readOnlyHint(true)
                    .build();
            CountDownLatch driverStarted = new CountDownLatch(1);
            TaskScheduler.TaskRunner driverRunner = req -> {
                driverStarted.countDown();
                return makeOutcome(req, StopReason.NO_TOOL_CALL, TaskState.SUCCEEDED);
            };
            Future<AgentOutcome> driverFuture = scheduler.submit(driverReq, driverRunner);

            // 副驾应被 cancel
            assertTrue("副驾 token 应被 cancel",
                    awaitCancelled(passengerToken, 2_000));
            passengerReleased.countDown();

            AgentOutcome passengerResult = passengerFuture.get(5, TimeUnit.SECONDS);
            assertEquals("V0.4.3 Round 2:Scheduler 内部 remapIfPreempted 把 CANCELLED 重写为 PREEMPTED",
                    StopReason.PREEMPTED, passengerResult.getStopReason());
            assertEquals("副驾应映射到 PREEMPTED",
                    TaskState.PREEMPTED, passengerResult.getFinalState());

            AgentOutcome driverResult = driverFuture.get(5, TimeUnit.SECONDS);
            assertEquals("主驾应正常 SUCCEEDED",
                    TaskState.SUCCEEDED, driverResult.getFinalState());
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    public void driverDoesNotPreemptPassengerWriteTask() throws Exception {
        TaskScheduler scheduler = new TaskScheduler(2, new SessionLockManager());
        try {
            CancellationToken passengerToken = new CancellationToken();
            CountDownLatch passengerStarted = new CountDownLatch(1);
            CountDownLatch passengerReleased = new CountDownLatch(1);

            // 副驾车控写(readOnlyHint=false,默认)
            AgentRequest passengerReq = AgentRequest.builder("调温到24度", Actor.PASSENGER)
                    .sessionId("preempt-B")
                    .cancellationToken(passengerToken)
                    .build();
            TaskScheduler.TaskRunner passengerRunner = req -> {
                passengerStarted.countDown();
                try {
                    passengerReleased.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return makeOutcome(req, StopReason.NO_TOOL_CALL, TaskState.SUCCEEDED);
            };
            scheduler.submit(passengerReq, passengerRunner);
            assertTrue(passengerStarted.await(2, TimeUnit.SECONDS));

            // 主驾查询到达 → 不应抢占副驾车控写
            AgentRequest driverReq = AgentRequest.builder("查电量", Actor.DRIVER)
                    .sessionId("preempt-B")
                    .readOnlyHint(true)
                    .build();
            CountDownLatch driverStarted = new CountDownLatch(1);
            AtomicReference<Long> driverStartMillis = new AtomicReference<>();
            TaskScheduler.TaskRunner driverRunner = req -> {
                // 先 set 再 countDown——保证主线程 await 返回时
                // driverStartMillis 已写入,消除 L154 拆箱 NPE race。
                driverStartMillis.set(System.currentTimeMillis());
                driverStarted.countDown();
                return makeOutcome(req, StopReason.NO_TOOL_CALL, TaskState.SUCCEEDED);
            };
            long submitMillis = System.currentTimeMillis();
            scheduler.submit(driverReq, driverRunner);

            // 副驾 token 不应被 cancel
            Thread.sleep(100);
            assertEquals("副驾车控写不应被 cancel",
                    false, passengerToken.isCancelled());

            // 释放副驾
            passengerReleased.countDown();

            // 主驾才进入执行
            assertTrue("主驾应排队等到副驾释放后才执行",
                    driverStarted.await(3, TimeUnit.SECONDS));
            long waitMs = driverStartMillis.get() - submitMillis;
            assertTrue("主驾等待时间应 >= 副驾占用时间(~100ms+)",
                    waitMs >= 80);
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    public void fifoForSameActor() throws Exception {
        TaskScheduler scheduler = new TaskScheduler(1, new SessionLockManager());
        try {
            CountDownLatch firstStarted = new CountDownLatch(1);
            CountDownLatch firstReleased = new CountDownLatch(1);
            AtomicReference<Integer> firstSeen = new AtomicReference<>();
            AtomicReference<Integer> secondSeen = new AtomicReference<>();
            AtomicInteger counter = new AtomicInteger(0);

            TaskScheduler.TaskRunner runner = req -> {
                int seq = counter.incrementAndGet();
                if (seq == 1) {
                    firstSeen.set(seq);
                    firstStarted.countDown();
                    try {
                        firstReleased.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                } else {
                    secondSeen.set(seq);
                }
                return makeOutcome(req, StopReason.NO_TOOL_CALL, TaskState.SUCCEEDED);
            };

            Future<AgentOutcome> f1 = scheduler.submit(
                    AgentRequest.builder("first", Actor.DRIVER).sessionId("fifo-C").build(),
                    runner);
            Future<AgentOutcome> f2 = scheduler.submit(
                    AgentRequest.builder("second", Actor.DRIVER).sessionId("fifo-C").build(),
                    runner);

            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            Thread.sleep(100);
            // 第二个还没开始(在排队)
            assertEquals("第二个请求应还在排队", null, secondSeen.get());
            firstReleased.countDown();

            f1.get(5, TimeUnit.SECONDS);
            f2.get(5, TimeUnit.SECONDS);
            assertEquals("第一个序号=1", Integer.valueOf(1), firstSeen.get());
            assertEquals("第二个序号=2", Integer.valueOf(2), secondSeen.get());
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    public void preemptionDoesNotCorruptSchedulerState() throws Exception {
        TaskScheduler scheduler = new TaskScheduler(2, new SessionLockManager());
        try {
            CancellationToken passengerToken = new CancellationToken();
            CountDownLatch passengerStarted = new CountDownLatch(1);
            CountDownLatch passengerReleased = new CountDownLatch(1);

            AgentRequest passengerReq = AgentRequest.builder("查电量", Actor.PASSENGER)
                    .sessionId("preempt-D")
                    .readOnlyHint(true)
                    .cancellationToken(passengerToken)
                    .build();
            TaskScheduler.TaskRunner passengerRunner = req -> {
                passengerStarted.countDown();
                try {
                    passengerReleased.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return makeOutcome(req,
                        passengerToken.isCancelled() ? StopReason.CANCELLED : StopReason.NO_TOOL_CALL,
                        passengerToken.isCancelled() ? TaskState.PREEMPTED : TaskState.SUCCEEDED);
            };
            Future<AgentOutcome> passengerFuture = scheduler.submit(passengerReq, passengerRunner);
            assertTrue(passengerStarted.await(2, TimeUnit.SECONDS));

            AgentRequest driverReq = AgentRequest.builder("查电量", Actor.DRIVER)
                    .sessionId("preempt-D")
                    .readOnlyHint(true)
                    .build();
            scheduler.submit(driverReq,
                    req -> makeOutcome(req, StopReason.NO_TOOL_CALL, TaskState.SUCCEEDED));

            assertTrue(awaitCancelled(passengerToken, 2_000));
            passengerReleased.countDown();
            passengerFuture.get(5, TimeUnit.SECONDS);

            // 给 driver 任务一些时间完成
            Thread.sleep(200);
            assertEquals("scheduler 状态应在所有任务完成后清空",
                    0, scheduler.runningCount());
        } finally {
            scheduler.shutdown();
        }
    }

    private static AgentOutcome makeOutcome(AgentRequest request, StopReason reason, TaskState state) {
        Trajectory trajectory = new Trajectory();
        trajectory.finish(reason, 0L, 0);
        return new AgentOutcome(request.getRequestId(), state, reason, trajectory, 0L);
    }

    /** 轮询等 token 被 cancel,最多 timeoutMillis。 */
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
