package com.matrix.agent.host.di;

import com.matrix.agent.host.rpc.DownloadServiceStub;
import com.matrix.agent.host.rpc.ModelServiceStub;
import com.matrix.agent.task.durable.PersistenceGate;

import android.os.IBinder;

/** Download domain Binder boundary; storage recovery remains owned by {@link DownloadRuntimeGraph}. */
final class DownloadGraph {
    private final DownloadServiceStub service;

    DownloadGraph(AppContainer container, PersistenceGate persistence,
            ModelServiceStub.CallerResolver callers) {
        service = new DownloadServiceStub(container.getAppContext(),
                container.getModelDownloadManager(), container.getModelDownloadDao(),
                container.getModelGatewayRepository(),
                container.getModelDownloadWorkScheduler(),
                container.getExecutorRegistry().networkExecutor(),
                container.getExecutorRegistry().timerScheduler(), persistence,
                container::isDownloadRecoveryComplete, container.getHttpClient().metadata(), callers);
    }

    IBinder binder() { return service.asBinder(); }
}
