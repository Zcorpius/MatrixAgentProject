package com.matrix.agent.launcher.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.matrix.agent.api.model.ConnectionTestResult;
import com.matrix.agent.api.model.ModelConfigInput;
import com.matrix.agent.api.model.ModelInfo;
import com.matrix.agent.api.model.ModelRuntimeStatus;
import com.matrix.agent.api.model.ModelProvisionInput;
import com.matrix.agent.client.ModelManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.IntConsumer;
import java.util.function.Consumer;

/** SDK-backed model data source; endpoint/key transport remains entirely inside the published SDK. */
public final class ModelRepository {
    private final LauncherHostGateway gateway;
    public ModelRepository(LauncherHostGateway gateway) { this.gateway = gateway; }
    public boolean isHostConnected() { return gateway.isConnected(); }
    public void provision(@NonNull String providerId, @NonNull String modelId,
            @NonNull String endpoint, @NonNull char[] secret,
            @NonNull IntConsumer completion,
            @NonNull Consumer<LauncherHostGateway.Result<Boolean>> receiver) {
        gateway.execute(agent -> {
            ModelManager manager = agent.getModelManager();
            if (manager == null) return false;
            manager.provisionCredential(new ModelProvisionInput(providerId, modelId, endpoint), secret,
                    UUID.randomUUID().toString(),
                    (handle, code) -> gateway.dispatchToMain(() -> completion.accept(code)));
            return true;
        }, receiver);
    }
    public void test(@NonNull String providerId,
            @NonNull Consumer<LauncherHostGateway.Result<ConnectionTestResult>> receiver) {
        gateway.execute(agent -> {
            ModelManager manager = agent.getModelManager();
            return manager == null ? null : manager.testConnection(
                    new ModelConfigInput(providerId, "host-keystore:" + providerId));
        }, receiver);
    }
    public void snapshot(@NonNull Consumer<LauncherHostGateway.Result<Snapshot>> receiver) {
        gateway.execute(agent -> {
            ModelManager manager = agent.getModelManager();
            return manager == null ? null : new Snapshot(manager.getRuntimeStatus(), manager.listModels());
        }, receiver);
    }
    public void select(@NonNull String modelId, @NonNull IntConsumer completion,
            @NonNull Consumer<LauncherHostGateway.Result<Boolean>> receiver) {
        gateway.execute(agent -> {
            ModelManager manager = agent.getModelManager();
            if (manager == null) return false;
            manager.setActiveModel(modelId, UUID.randomUUID().toString(),
                    (handle, code) -> gateway.dispatchToMain(() -> completion.accept(code)));
            return true;
        }, receiver);
    }
    public static final class Snapshot {
        @Nullable public final ModelRuntimeStatus runtime;
        @NonNull public final List<ModelInfo> models;
        public Snapshot(@Nullable ModelRuntimeStatus runtime, @Nullable List<ModelInfo> models) {
            this.runtime = runtime;
            this.models = Collections.unmodifiableList(models == null ? Collections.emptyList() : new ArrayList<>(models));
        }
    }
}
