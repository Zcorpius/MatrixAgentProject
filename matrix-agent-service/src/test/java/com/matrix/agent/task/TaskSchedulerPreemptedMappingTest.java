package com.matrix.agent.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.matrix.agent.task.identity.Actor;
import com.matrix.agent.task.identity.AgentRequest;
import com.matrix.agent.task.identity.CancellationToken;
import com.matrix.agent.data.session.SessionLockManager;

/**
 * TaskScheduler PREEMPTED 重映射单元测试。
 *
 * <p>Scheduler.tryPreemptPassengerReadOnly 自己 cancel 的 task,
 * runner 返回 CANCELLED+CANCELLED 后由 Scheduler.remapIfPreempted 重写为
 * StopReason.PREEMPTED + TaskState.PREEMPTED。旧契约把映射责任推给调用方,
 * 生产路径下 Repository 没做这层映射,导致终态永远是 CANCELLED+CANCELLED。
 */
public final class TaskSchedulerPreemptedMappingTest {

    @Test
    public void schedulerCancelledTaskIsRemappedToPreempted() throws Exception {
        TaskScheduler scheduler = new TaskScheduler(2, new SessionLockManager());
        try {
            CancellationToken passengerToken = new CancellationToken();
            CountDownLatch passengerStarted = new CountDownLatch(1);
            CountDownLatch passengerReleased = new CountDownLatch(1);
            AtomicReference<AgentOutcome> passengerOutcome = new AtomicReference<>();

            AgentRequest passengerReq = AgentRequest.builder("查电量", Actor.PASSENGER)
                    .sessionId("preempt-remap")
                    .readOnlyHint(true)
                    .cancellationToken(passengerToken)
                    .build();
            // 关键 runner:token 被 cancel 后只返回 CANCELLED+CANCELLED(模拟真实 AgentEngine),
            // 不在 runner 里自己判断 PREEMPTED——验证 Scheduler 的重映射责任。
            TaskScheduler.TaskRunner passengerRunner = req -> {
                passengerStarted.countDown();
                try {
                    passengerReleased.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                AgentOutcome outcome = makeOutcome(req,
                        passengerToken.isCancelled() ? StopReason.CANCELLED : StopReason.NO_TOOL_CALL,
                        passengerToken.isCancelled() ? TaskState.CANCELLED : TaskState.SUCCEEDED);
                passengerOutcome.set(outcome);
                return outcome;
            };
            Future<AgentOutcome> passengerFuture = scheduler.submit(passengerReq, passengerRunner);
            assertTrue(passengerStarted.await(2, TimeUnit.SECONDS));

            AgentRequest driverReq = AgentRequest.builder("查胎压", Actor.DRIVER)
                    .sessionId("preempt-remap")
                    .readOnlyHint(true)
                    .build();
            Future<AgentOutcome> driverFuture = scheduler.submit(driverReq,
                    req -> makeOutcome(req, StopReason.NO_TOOL_CALL, TaskState.SUCCEEDED));

            assertTrue("副驾 token 应被 cancel", awaitCancelled(passengerToken, 2_000));
            passengerReleased.countDown();

            AgentOutcome passengerResult = passengerFuture.get(5, TimeUnit.SECONDS);
            assertEquals("runner 原始 outcome 是 CANCELLED+CANCELLED",
                    StopReason.CANCELLED, passengerOutcome.get().getStopReason());
            assertEquals("runner 原始 finalState 是 CANCELLED",
                    TaskState.CANCELLED, passengerOutcome.get().getFinalState());
            assertEquals("Scheduler 应重映射 stopReason 为 PREEMPTED",
                    StopReason.PREEMPTED, passengerResult.getStopReason());
            assertEquals("Scheduler 应重映射 finalState 为 PREEMPTED",
                    TaskState.PREEMPTED, passengerResult.getFinalState());

            AgentOutcome driverResult = driverFuture.get(5, TimeUnit.SECONDS);
            assertEquals(TaskState.SUCCEEDED, driverResult.getFinalState());
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    public void schedulerDoesNotRemapNonCancelledOutcomes() throws Exception {
        // 即便 task 被标记 preempted,如果 runner 返回 SUCCEEDED(异常路径),
        // Scheduler 不应错改 SUCCEEDED 为 PREEMPTED——防御性 not override
        TaskScheduler scheduler = new TaskScheduler(2, new SessionLockManager());
        try {
            CancellationToken passengerToken = new CancellationToken();
            CountDownLatch passengerStarted = new CountDownLatch(1);
            CountDownLatch passengerReleased = new CountDownLatch(1);

            AgentRequest passengerReq = AgentRequest.builder("查电量", Actor.PASSENGER)
                    .sessionId("preempt-no-remap")
                    .readOnlyHint(true)
                    .cancellationToken(passengerToken)
                    .build();
            // runner 故意返回 SUCCEEDED(模拟 cancel 后 Engine 仍跑完)
            TaskScheduler.TaskRunner passengerRunner = req -> {
                passengerStarted.countDown();
                try {
                    passengerReleased.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return makeOutcome(req, StopReason.NO_TOOL_CALL, TaskState.SUCCEEDED);
            };
            Future<AgentOutcome> passengerFuture = scheduler.submit(passengerReq, passengerRunner);
            assertTrue(passengerStarted.await(2, TimeUnit.SECONDS));

            scheduler.submit(driverReq("preempt-no-remap"),
                    req -> makeOutcome(req, StopReason.NO_TOOL_CALL, TaskState.SUCCEEDED));

            assertTrue(awaitCancelled(passengerToken, 2_000));
            passengerReleased.countDown();

            AgentOutcome passengerResult = passengerFuture.get(5, TimeUnit.SECONDS);
            assertEquals("runner 返回 SUCCEEDED 时不重映射",
                    TaskState.SUCCEEDED, passengerResult.getFinalState());
            assertEquals("runner 返回 NO_TOOL_CALL 时不重映射",
                    StopReason.NO_TOOL_CALL, passengerResult.getStopReason());
        } finally {
            scheduler.shutdown();
        }
    }

    private static AgentRequest driverReq(String sessionId) {
        return AgentRequest.builder("查胎压", Actor.DRIVER)
                .sessionId(sessionId)
                .readOnlyHint(true)
                .build();
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
