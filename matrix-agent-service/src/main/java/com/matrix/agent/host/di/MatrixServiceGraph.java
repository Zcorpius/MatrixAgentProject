package com.matrix.agent.host.di;

import com.matrix.agent.host.rpc.ModelServiceStub;
import com.matrix.agent.task.durable.PersistenceGate;
import com.matrix.agent.task.durable.PersistentTaskManager;

import android.app.Application;
import android.os.IBinder;

import com.matrix.agent.api.common.MatrixServiceConstants;

/** Owns Host domain graphs; Android Service only routes Binder calls and lifecycle. */
public final class MatrixServiceGraph {
    private final PersistenceGate persistence;
    private final TaskGraph tasks;
    private final ModelGraph model;
    private final DownloadGraph download;
    private final VoiceGraph voice;

    public MatrixServiceGraph(AppContainer container, ModelServiceStub.CallerResolver callers) {
        persistence = new PersistenceGate(container.getMatrixDatabase());
        tasks = new TaskGraph(container.getMatrixDatabase(),
                container.getAgentRuntimeRepository(),
                container.getExecutorRegistry().hostDispatcherExecutor(),
                container.getExecutorRegistry().dbExecutor());
        model = new ModelGraph(container, persistence, callers);
        download = new DownloadGraph(container, persistence, callers);
        voice = new VoiceGraph((Application) container.getAppContext(), container.getModelDownloadDao(),
                container.getExecutorRegistry().voiceDownloadExecutor(), persistence, callers);
    }

    public boolean persistenceAvailable() { return persistence.isAvailable(); }
    public boolean tasksAvailable() { return tasks.isAvailable(); }
    public String persistenceUnavailableReason() { return persistence.unavailableReason(); }
    public PersistentTaskManager tasks() { return tasks.requireManager(); }
    public IBinder modelBinder() { return model.binder(); }
    public IBinder downloadBinder() { return download.binder(); }
    public IBinder voiceBinder() { return voice.binder(); }
    public int featureFlags() {
        int flags = MatrixServiceConstants.FEATURE_DURABLE_TASKS
                | MatrixServiceConstants.FEATURE_MODEL_DOMAIN
                | MatrixServiceConstants.FEATURE_DOWNLOAD_DOMAIN
                | MatrixServiceConstants.FEATURE_PERSISTENCE_GATE;
        return voice.isAvailable() ? flags | MatrixServiceConstants.FEATURE_VOICE_DOMAIN : flags;
    }
    public void shutdown() {
        voice.shutdown();
        tasks.shutdown();
    }
}
