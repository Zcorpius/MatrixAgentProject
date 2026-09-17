package com.matrix.agent.launcher.data;

import androidx.annotation.NonNull;

import com.matrix.agent.api.agent.AgentOperationResult;
import com.matrix.agent.api.agent.AgentRequest;
import com.matrix.agent.api.agent.AgentTaskEvent;
import com.matrix.agent.api.agent.AgentTaskHandle;
import com.matrix.agent.api.agent.AgentTaskSnapshot;
import com.matrix.agent.api.agent.IAgentTaskCallback;
import com.matrix.agent.client.MatrixAgentManager;

import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

/** SDK-backed task data source.  Presentation code never sees a Manager instance. */
public final class AgentTaskRepository {
    private final LauncherHostGateway gateway;
    public AgentTaskRepository(LauncherHostGateway gateway) { this.gateway = gateway; }
    public boolean isHostConnected() { return gateway.isConnected(); }

    /**
     * Creates a task without an ephemeral submit callback.  Once a handle is returned, callers
     * subscribe from its accepted sequence, which atomically replays the gap and gives the page
     * one closeable callback lifetime.  Registering both callbacks would duplicate every event.
     */
    public void submit(@NonNull String text,
            @NonNull Consumer<LauncherHostGateway.Result<AgentTaskHandle>> receiver) {
        gateway.execute(agent -> {
            MatrixAgentManager manager = agent.getAgentManager();
            return manager == null ? null : manager.submit(new AgentRequest(UUID.randomUUID().toString(),
                    "launcher", text, AgentRequest.INPUT_TEXT, Locale.getDefault().toLanguageTag()),
                    (IAgentTaskCallback) null);
        }, receiver);
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
