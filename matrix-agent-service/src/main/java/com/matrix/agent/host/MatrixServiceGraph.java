package com.matrix.agent.host;

import android.app.Application;
import android.os.IBinder;

import com.matrix.agent.api.common.MatrixServiceConstants;

/** Owns Host domain graphs; Android Service only routes Binder calls and lifecycle. */
final class MatrixServiceGraph {
    private final PersistenceGate persistence;
    private final TaskGraph tasks;
    private final ModelGraph model;
    private final DownloadGraph download;
    private final VoiceGraph voice;

    MatrixServiceGraph(AppContainer container, ModelServiceStub.CallerResolver callers) {
        persistence = new PersistenceGate(container.getMatrixDatabase());
        tasks = new TaskGraph(container.getMatrixDatabase(),
                container.getAgentRuntimeRepository(),
                container.getExecutorRegistry().hostDispatcherExecutor(),
                container.getExecutorRegistry().dbExecutor());
        model = new ModelGraph(container, persistence, callers);
        download = new DownloadGraph(container, persistence, callers);
        voice = new VoiceGraph((Application) container.getAppContext(), persistence, callers);
    }

    boolean persistenceAvailable() { return persistence.isAvailable(); }
    boolean tasksAvailable() { return tasks.isAvailable(); }
    String persistenceUnavailableReason() { return persistence.unavailableReason(); }
    PersistentTaskManager tasks() { return tasks.requireManager(); }
    IBinder modelBinder() { return model.binder(); }
    IBinder downloadBinder() { return download.binder(); }
    IBinder voiceBinder() { return voice.binder(); }
    int featureFlags() {
        int flags = MatrixServiceConstants.FEATURE_DURABLE_TASKS
                | MatrixServiceConstants.FEATURE_MODEL_DOMAIN
                | MatrixServiceConstants.FEATURE_DOWNLOAD_DOMAIN
                | MatrixServiceConstants.FEATURE_PERSISTENCE_GATE;
        return voice.isAvailable() ? flags | MatrixServiceConstants.FEATURE_VOICE_DOMAIN : flags;
    }
    void shutdown() {
        voice.shutdown();
        tasks.shutdown();
    }
}
