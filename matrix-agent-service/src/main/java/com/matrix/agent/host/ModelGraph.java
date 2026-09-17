package com.matrix.agent.host;

import android.os.IBinder;

/** Model domain boundary: owns its Binder facade and Host-only dependencies. */
final class ModelGraph {
    private final ModelServiceStub service;

    ModelGraph(AppContainer container, PersistenceGate persistence,
            ModelServiceStub.CallerResolver callers) {
        service = new ModelServiceStub(container.getModelGatewayRepository(),
                container.getAgentRuntimeRepository(), container.getModelDownloadDao(),
                container.getExecutorRegistry().networkExecutor(),
                container.getExecutorRegistry().modelExecutor(), persistence, callers);
    }

    IBinder binder() { return service.asBinder(); }
}
