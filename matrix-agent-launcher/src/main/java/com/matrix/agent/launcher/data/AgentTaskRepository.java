package com.matrix.agent.launcher.data;

import android.os.SystemClock;

import androidx.annotation.NonNull;

import com.matrix.agent.api.agent.AgentOperationResult;
import com.matrix.agent.api.agent.AgentRequest;
import com.matrix.agent.api.agent.AgentTaskEvent;
import com.matrix.agent.api.agent.AgentTaskHandle;
import com.matrix.agent.api.agent.AgentTaskSnapshot;
import com.matrix.agent.api.agent.IAgentTaskCallback;
import com.matrix.agent.api.common.MatrixErrorCode;
import com.matrix.agent.client.MatrixAgentManager;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** SDK-backed task data source.  Presentation code never sees a Manager instance. */
public final class AgentTaskRepository {
    /** A cold Host may expose its Binder before durable task recovery completes. */
    private static final long TASK_STARTUP_RETRY_WINDOW_MS = 4_000L;
    private static final long TASK_STARTUP_RETRY_DELAY_MS = 100L;

    private final LauncherHostGateway gateway;
    public AgentTaskRepository(LauncherHostGateway gateway) { this.gateway = gateway; }
    public boolean isHostConnected() { return gateway.isConnected(); }

    /**
     * Creates a task without an ephemeral submit callback.  Once a handle is returned, callers
     * subscribe from its accepted sequence, which atomically replays the gap and gives the page
     * one closeable callback lifetime.  Registering both callbacks would duplicate every event.
     */
    public AutoCloseable submit(@NonNull String text,
            @NonNull Consumer<LauncherHostGateway.Result<AgentTaskHandle>> receiver) {
        AtomicBoolean cancelled = new AtomicBoolean();
        AgentRequest request = new AgentRequest(UUID.randomUUID().toString(), "launcher", text,
                AgentRequest.INPUT_TEXT, Locale.getDefault().toLanguageTag());
        submitAttempt(request, SystemClock.elapsedRealtime() + TASK_STARTUP_RETRY_WINDOW_MS,
                cancelled, receiver);
        return () -> cancelled.set(true);
    }

    private void submitAttempt(@NonNull AgentRequest request, long retryDeadlineMillis,
            @NonNull AtomicBoolean cancelled,
            @NonNull Consumer<LauncherHostGateway.Result<AgentTaskHandle>> receiver) {
        if (cancelled.get()) return;
        gateway.execute(agent -> {
            MatrixAgentManager manager = agent.getAgentManager();
            return manager == null ? null : manager.submit(request, (IAgentTaskCallback) null);
        }, result -> {
            if (cancelled.get()) return;
            AgentTaskHandle handle = result.value;
            if (result.isSuccess() && handle != null
                    && handle.errorCode == MatrixErrorCode.SERVICE_NOT_READY
                    && SystemClock.elapsedRealtime() < retryDeadlineMillis) {
                try {
                    ScheduledFuture<?> retry = gateway.schedule(
                            () -> submitAttempt(request, retryDeadlineMillis, cancelled, receiver),
                            TASK_STARTUP_RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
                    if (!cancelled.get()) return;
                    retry.cancel(false);
                } catch (RuntimeException unavailable) {
                    receiver.accept(LauncherHostGateway.Result.failure(unavailable));
                }
                return;
            }
            receiver.accept(result);
        });
    }
    public void snapshot(@NonNull String taskId,
            @NonNull Consumer<LauncherHostGateway.Result<AgentTaskSnapshot>> receiver) {
        gateway.execute(agent -> {
            MatrixAgentManager manager = agent.getAgentManager();
            return manager == null ? null : manager.getTaskSnapshot(taskId);
        }, receiver);
    }
    public void cancel(@NonNull String taskId,
            @NonNull Consumer<LauncherHostGateway.Result<AgentOperationResult>> receiver) {
        gateway.execute(agent -> {
            MatrixAgentManager manager = agent.getAgentManager();
            return manager == null ? null : manager.cancelTask(taskId, UUID.randomUUID().toString());
        }, receiver);
    }
    public void subscribe(@NonNull String taskId, long afterSequence,
            @NonNull Consumer<AgentTaskEvent> events,
            @NonNull Consumer<LauncherHostGateway.Result<AutoCloseable>> receiver) {
        gateway.execute(agent -> {
            MatrixAgentManager manager = agent.getAgentManager();
            return manager == null ? null : manager.subscribeTask(taskId, afterSequence,
                    event -> gateway.dispatchToMain(() -> events.accept(event)));
        }, receiver);
    }
}
