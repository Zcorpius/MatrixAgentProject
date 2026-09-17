package com.matrix.agent.task;

import android.util.Log;

import com.matrix.agent.task.identity.AgentRequest;

import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 调度器异常后的终态语义：尤其保护写操作不能被误报为已取消或超时。 */
public final class TaskDispatchRecovery {
    private static final String TAG = "MatrixAgent";
    private static final long WRITE_GRACE_MILLIS = 500L;

    private TaskDispatchRecovery() { }

    public static AgentOutcome awaitWriteConvergence(Future<AgentOutcome> future,
            AgentRequest request, long startedNanos, String message) {
        if (future != null) {
            try {
                return future.get(WRITE_GRACE_MILLIS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException | CancellationException | ExecutionException error) {
                future.cancel(true);
                Log.w(TAG, "[DispatchRecovery] write convergence unresolved req="
                        + request.getRequestId() + " cause=" + error.getClass().getSimpleName());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                future.cancel(true);
            }
        }
        return terminal(request, TaskState.EXECUTION_UNKNOWN, StopReason.EXECUTION_UNKNOWN,
                message, startedNanos);
    }

    public static AgentOutcome terminal(AgentRequest request, TaskState state,
            StopReason reason, String message, long startedNanos) {
        Trajectory trajectory = new Trajectory();
        long elapsed = (System.nanoTime() - startedNanos) / 1_000_000L;
        trajectory.finish(reason, elapsed, 0);
        return new AgentOutcome(request.getRequestId(), state, reason, trajectory, elapsed);
    }
}
