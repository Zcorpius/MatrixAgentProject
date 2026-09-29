package com.matrix.agent.task;

import static org.junit.Assert.*;
import com.matrix.agent.identity.*;
import com.matrix.agent.session.SessionLockManager;
import com.matrix.agent.task.scheduler.TaskScheduler;
import java.util.concurrent.*;
import org.junit.Test;

public final class AutomaticTaskArbitrationTest {
    @Test public void activeInteractiveTaskPreventsAutomaticAdmission() throws Exception {
        var scheduler = new TaskScheduler(1, new SessionLockManager());
        var started = new CountDownLatch(1); var finish = new CountDownLatch(1);
        try {
            var request = AgentRequest.builder("用户任务", Actor.DRIVER).sessionId("interactive").arbitrationKey("demo-vehicle").build();
            var future = scheduler.submit(request, ignored -> { started.countDown(); try { finish.await(2, TimeUnit.SECONDS); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); } return success(ignored); });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertNull(scheduler.tryAutomaticLease(new CancellationToken(), true));
            finish.countDown(); future.get(2, TimeUnit.SECONDS);
            try (var lease = scheduler.tryAutomaticLease(new CancellationToken(), true)) {
                assertNotNull(lease); assertTrue(scheduler.holdsAutomaticLease());
            }
            assertFalse(scheduler.holdsAutomaticLease());
        } finally { finish.countDown(); scheduler.shutdown(); }
    }
    @Test public void interactiveArrivalCancelsReadOnlyAutomaticButWaitsForLeaseRelease() throws Exception {
        var scheduler = new TaskScheduler(1, new SessionLockManager());
        var token = new CancellationToken(); var began = new CountDownLatch(1);
        try {
            Future<AgentOutcome> interactive;
            try (var lease = scheduler.tryAutomaticLease(token, true)) {
                assertNotNull(lease);
                interactive = scheduler.submit(AgentRequest.builder("用户优先", Actor.DRIVER).sessionId("interactive").arbitrationKey("demo-vehicle").build(),
                        ignored -> { began.countDown(); return success(ignored); });
                assertTrue(token.isCancelled());
                assertFalse(began.await(50, TimeUnit.MILLISECONDS));
            }
            assertTrue(began.await(2, TimeUnit.SECONDS)); interactive.get(2, TimeUnit.SECONDS);
        } finally { scheduler.shutdown(); }
    }
    private static AgentOutcome success(AgentRequest request) {
        var trajectory = new Trajectory(); trajectory.finish(StopReason.NO_TOOL_CALL, 0L, 0);
        return new AgentOutcome(request.getRequestId(), TaskState.SUCCEEDED, StopReason.NO_TOOL_CALL, trajectory, 0L);
    }

}
