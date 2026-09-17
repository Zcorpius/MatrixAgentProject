package com.matrix.agent.task;

import android.util.Log;

import com.matrix.agent.data.audit.AuditRepository;
import com.matrix.agent.task.identity.AgentRequest;
import com.matrix.agent.task.identity.CancellationToken;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 任务提交、deadline 收敛和兜底审计的单一协调器。 */
public final class TaskDispatchCoordinator {
    private static final String TAG = "MatrixAgent";
    private static final long SAFETY_MARGIN_MILLIS = 20L;
    private final TaskScheduler scheduler;
    private final AuditRepository auditRepository;
    private final InFlightTaskRegistry inFlightTasks;

    public TaskDispatchCoordinator(TaskScheduler scheduler, AuditRepository auditRepository,
            InFlightTaskRegistry inFlightTasks) {
        this.scheduler = scheduler;
        this.auditRepository = auditRepository;
        this.inFlightTasks = inFlightTasks;
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
        auditRepository.persist(outcome, request);
        return outcome;
    }
}
