package com.matrix.agent.task.scheduler;
import com.matrix.agent.task.*;

import android.util.Log;

import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.identity.CancellationToken;
import com.matrix.agent.task.port.TaskAuditSink;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 任务提交、deadline 收敛和兜底审计的单一协调器。 */
public final class TaskDispatchCoordinator {
    private static final String TAG = "MatrixAgent";
    private static final long SAFETY_MARGIN_MILLIS = 20L;
    private final TaskScheduler scheduler;
    private final TaskAuditSink auditSink;
    private final InFlightTaskRegistry inFlightTasks;

    public TaskDispatchCoordinator(TaskScheduler scheduler, TaskAuditSink auditSink,
            InFlightTaskRegistry inFlightTasks) {
        this.scheduler = scheduler;
        this.auditSink = auditSink == null ? TaskAuditSink.NOOP : auditSink;
        this.inFlightTasks = inFlightTasks;
    }

    /** Dedicated automatic worker already owns arbitration. Do not queue it a second time. */
    public AgentOutcome dispatchAutomatic(AgentRequest request, CancellationToken token, AgentEngine engine) {
        if (!scheduler.holdsAutomaticLease()) throw new IllegalStateException("automatic arbitration lease required");
        long started = System.nanoTime(); AgentOutcome outcome;
        inFlightTasks.register(token);
        try { outcome = engine.execute(request); }
        catch (RuntimeException failure) {
            token.cancel();
            outcome = TaskDispatchRecovery.terminal(request,
                    request.isReadOnlyHint() ? TaskState.FAILED : TaskState.EXECUTION_UNKNOWN,
                    request.isReadOnlyHint() ? StopReason.PROTOCOL_ERROR : StopReason.EXECUTION_UNKNOWN,
                    "automatic execution interrupted", started);
        } finally { inFlightTasks.unregister(token); }
        auditSink.persist(outcome, request); return outcome;
    }

    public AgentOutcome dispatch(AgentRequest request, CancellationToken token, AgentEngine engine) {
        AgentOutcome outcome;
        Future<AgentOutcome> future = null;
        long started = System.nanoTime();
        inFlightTasks.register(token);
        try {
            future = scheduler.submit(request, engine::execute);
            outcome = future.get(Math.max(1L, request.remainingMillis() - SAFETY_MARGIN_MILLIS),
                    TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            token.cancel();
            if (request.isReadOnlyHint()) {
                if (future != null) future.cancel(true);
                outcome = TaskDispatchRecovery.terminal(request, TaskState.TIMED_OUT,
                        StopReason.TIMEOUT, "scheduler future timeout", started);
            } else {
                outcome = TaskDispatchRecovery.awaitWriteConvergence(future, request, started,
                        "scheduler future timeout");
            }
        } catch (ExecutionException error) {
            token.cancel();
            if (future != null) future.cancel(true);
            Throwable cause = error.getCause() == null ? error : error.getCause();
            outcome = TaskDispatchRecovery.terminal(request, TaskState.FAILED,
                    StopReason.PROTOCOL_ERROR,
                    "scheduler execution failed: " + cause.getClass().getSimpleName(), started);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            token.cancel();
            if (future != null) future.cancel(true);
            boolean readOnly = request.isReadOnlyHint();
            outcome = TaskDispatchRecovery.terminal(request,
                    readOnly ? TaskState.CANCELLED : TaskState.EXECUTION_UNKNOWN,
                    readOnly ? StopReason.CANCELLED : StopReason.EXECUTION_UNKNOWN,
                    "scheduler future interrupted", started);
        } finally {
            inFlightTasks.unregister(token);
        }
        Log.i(TAG, "[Dispatch] <- state=" + outcome.getFinalState()
                + " stop=" + outcome.getStopReason());
        auditSink.persist(outcome, request);
        return outcome;
    }
}
