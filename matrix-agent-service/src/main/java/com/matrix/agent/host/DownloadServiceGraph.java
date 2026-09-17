package com.matrix.agent.host;

import android.os.IBinder;

/** Download domain Binder boundary; storage recovery remains owned by {@link DownloadGraph}. */
final class DownloadServiceGraph {
    private final DownloadServiceStub service;

    DownloadServiceGraph(AppContainer container, PersistenceGate persistence,
            ModelServiceStub.CallerResolver callers) {
        service = new DownloadServiceStub(container.getAppContext(),
                container.getModelDownloadManager(), container.getModelDownloadDao(),
                container.getExecutorRegistry().networkExecutor(),
                container.getExecutorRegistry().timerScheduler(), persistence, callers);
    }

    IBinder binder() { return service.asBinder(); }
}
