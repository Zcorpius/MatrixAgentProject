package com.matrix.agent.host;

import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import androidx.annotation.NonNull;

import com.matrix.agent.api.common.MatrixErrorCode;
import com.matrix.agent.api.model.ConnectionTestResult;
import com.matrix.agent.api.model.IModelCallback;
import com.matrix.agent.api.model.IModelService;
import com.matrix.agent.api.model.ModelConfigInput;
import com.matrix.agent.api.model.ModelInfo;
import com.matrix.agent.api.model.ModelOperationHandle;
import com.matrix.agent.api.model.ModelProvisionInput;
import com.matrix.agent.api.model.ModelRuntimeStatus;
import com.matrix.agent.data.db.ModelDownloadEntity;
import com.matrix.agent.model.ApiProtocol;
import com.matrix.agent.model.ModelConfig;
import com.matrix.agent.model.ModelGatewayRepository;
import com.matrix.agent.model.ModelProviderPreset;
import com.matrix.agent.task.AgentRuntimeRepository;
import com.matrix.agent.task.identity.CancellationToken;
import com.matrix.agent.data.db.ModelDownloadDao;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Host-owned implementation of the model domain.  It deliberately accepts no endpoint or secret. */
final class ModelServiceStub extends IModelService.Stub {
    private static final long CONNECTION_TIMEOUT_SECONDS = 20L;
    private final ModelGatewayRepository models;
    private final AgentRuntimeRepository runtime;
    private final ModelDownloadDao downloads;
    private final PersistenceGate persistenceGate;
    private final ExecutorService io;
    /** One serialized lane for native runtime construction, probing and activation. */
    private final ExecutorService localModel;
    private final CallerResolver callerResolver;

    interface CallerResolver { CallerContext caller(); }

    ModelServiceStub(@NonNull ModelGatewayRepository models,
            @NonNull AgentRuntimeRepository runtime, ModelDownloadDao downloads,
            @NonNull ExecutorService io, @NonNull ExecutorService localModel,
            @NonNull PersistenceGate persistenceGate,
            @NonNull CallerResolver callerResolver) {
        this.models = models;
        this.runtime = runtime;
        this.downloads = downloads;
        this.persistenceGate = persistenceGate;
        this.io = io;
        this.localModel = localModel;
        this.callerResolver = callerResolver;
    }

    @Override public List<ModelInfo> listModels() {
        callerResolver.caller();
        List<ModelInfo> result = new ArrayList<>();
        ModelConfig active = models.load();
        if (active != null) {
            result.add(new ModelInfo(active.model, active.displayName, active.providerId, true, true));
        }
        if (downloads != null) {
            for (ModelDownloadEntity item : downloads.getCompleted()) {
                if (active == null || !item.modelName.equals(active.model)) {
                    result.add(new ModelInfo(item.modelName,
                            item.displayName == null ? item.modelName : item.displayName,
                            "on_device", false, true));
                }
            }
        }
        return result;
    }

    @Override public ModelRuntimeStatus getRuntimeStatus() {
        callerResolver.caller();
        ModelConfig config = models.load();
        if (config == null) {
            return new ModelRuntimeStatus(null, false, ModelRuntimeStatus.BACKEND_NONE,
                    MatrixErrorCode.SERVICE_NOT_READY);
        }
        int backend = config.protocol == ApiProtocol.ON_DEVICE
                ? ModelRuntimeStatus.BACKEND_ON_DEVICE : ModelRuntimeStatus.BACKEND_CLOUD;
        return new ModelRuntimeStatus(config.model, true, backend, MatrixErrorCode.SUCCESS);
    }

    @Override public ConnectionTestResult testConnection(ModelConfigInput input) {
        callerResolver.caller();
        if (!persistenceGate.isAvailable()) return connectionFailure(MatrixErrorCode.PERSISTENCE_UNAVAILABLE);
        try {
            ModelConfig config = requireExistingConfig(input);
            long started = android.os.SystemClock.elapsedRealtime();
            // Native probing may map a multi-GB model.  It must not occupy the catalog/download
            // lane, nor race an activation of the same native runtime.
            ExecutorService executor = config.protocol == ApiProtocol.ON_DEVICE ? localModel : io;
            CancellationToken cancellationToken = new CancellationToken();
            java.util.concurrent.Future<String> probe = executor.submit(
                    () -> models.testConnection(config, cancellationToken));
            try {
                probe.get(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException timeout) {
                // Future.get timeout alone abandons the Binder caller but leaves a transport or
                // MNN load running on its scarce lane.  Cancel through the domain token first
                // (disconnect / nativeCancel), then interrupt as a secondary escape hatch.
                cancellationToken.cancel();
                probe.cancel(true);
                throw timeout;
            } catch (InterruptedException interrupted) {
                cancellationToken.cancel();
                probe.cancel(true);
                Thread.currentThread().interrupt();
                throw interrupted;
            }
            return new ConnectionTestResult(true, android.os.SystemClock.elapsedRealtime() - started,
                    MatrixErrorCode.SUCCESS, "connection verified");
        } catch (java.util.concurrent.TimeoutException timeout) {
            return new ConnectionTestResult(false, CONNECTION_TIMEOUT_SECONDS * 1000L,
                    MatrixErrorCode.TIMED_OUT, "connection timed out");
        } catch (Exception error) {
            return connectionFailure(MatrixErrorCode.TASK_FAILED);
        }
    }

    @Override public ModelOperationHandle provisionCredential(ModelProvisionInput input,
            ParcelFileDescriptor secretPipe, String operationId, IModelCallback callback) {
        callerResolver.caller();
        Provisioning provisioning = requireProvisioning(input);
        String safeProvider = provisioning.provider.id;
        String safeOperation = HostInputValidator.requireOperationId(operationId);
        if (!persistenceGate.isAvailable()) {
            closeQuietly(secretPipe);
            return failed(safeOperation, safeProvider, MatrixErrorCode.PERSISTENCE_UNAVAILABLE, callback);
        }
        if (secretPipe == null) {
            return failed(safeOperation, safeProvider, MatrixErrorCode.INVALID_ARGUMENT, callback);
        }
        ModelOperationHandle pending = new ModelOperationHandle(safeOperation, safeProvider,
                ModelOperationHandle.STATE_PENDING);
        try {
            io.execute(() -> {
                byte[] secret = null;
                int code = MatrixErrorCode.SUCCESS;
                ModelOperationHandle completed;
                try {
                    secret = readSecret(secretPipe, provisioning.provider.apiKeyRequired);
                    String value = new String(secret, java.nio.charset.StandardCharsets.UTF_8);
                    ModelConfig config = new ModelConfig(provisioning.provider.id,
                            provisioning.provider.name, provisioning.provider.protocol,
                            provisioning.endpoint, provisioning.modelId, value,
                            provisioning.provider.apiKeyRequired);
                    // A saved configuration is also the active configuration exposed through
                    // list/status. Build runtime dependencies before persisting, then publish
                    // them under the same mutation lock used by selection and boot recovery.
                    models.withModelMutationLock(() -> {
                        activateSelectedModel(config);
                        return null;
                    });
                    completed = new ModelOperationHandle(safeOperation, safeProvider,
                            ModelOperationHandle.STATE_SUCCEEDED);
                } catch (Exception error) {
                    code = MatrixErrorCode.INVALID_ARGUMENT;
                    completed = new ModelOperationHandle(safeOperation, safeProvider,
                            ModelOperationHandle.STATE_FAILED);
                } finally {
                    if (secret != null) java.util.Arrays.fill(secret, (byte) 0);
                    closeQuietly(secretPipe);
                }
                notifyFinished(callback, completed, code);
            });
        } catch (java.util.concurrent.RejectedExecutionException full) {
            closeQuietly(secretPipe);
            return failed(safeOperation, safeProvider, MatrixErrorCode.OVERLOADED, callback);
        }
        return pending;
    }

    @Override public ModelOperationHandle setActiveModel(String modelId, String operationId,
            IModelCallback callback) {
        callerResolver.caller();
        String safeModel = requireModelId(modelId);
        String safeOperation = HostInputValidator.requireOperationId(operationId);
        if (!persistenceGate.isAvailable()) {
            return failed(safeOperation, safeModel, MatrixErrorCode.PERSISTENCE_UNAVAILABLE, callback);
        }
        ModelOperationHandle pending = new ModelOperationHandle(safeOperation, safeModel,
                ModelOperationHandle.STATE_PENDING);
        try {
            // Gateway construction is the activation boundary: for an on-device model it loads
            // native state, so serialize it with inference/probing.  Do not persist the selected
            // config until this boundary succeeds; otherwise a failed load would poison startup
            // with an "active" model that can never be activated.
            localModel.execute(() -> {
                int code = MatrixErrorCode.SUCCESS;
                ModelOperationHandle finalHandle;
                try {
                ModelConfig selected = configForModel(safeModel);
                    models.withModelMutationLock(() -> {
                        if (selected.protocol == ApiProtocol.ON_DEVICE) {
                            models.withOnDeviceMutationLock(() -> {
                                activateSelectedModel(selected);
                                return null;
                            });
                        } else {
                            activateSelectedModel(selected);
                        }
                        return null;
                    });
                    finalHandle = new ModelOperationHandle(safeOperation, safeModel,
                            ModelOperationHandle.STATE_SUCCEEDED);
                } catch (Exception ignored) {
                    code = MatrixErrorCode.TASK_FAILED;
                    finalHandle = new ModelOperationHandle(safeOperation, safeModel,
                            ModelOperationHandle.STATE_FAILED);
                }
                notifyFinished(callback, finalHandle, code);
            });
        } catch (java.util.concurrent.RejectedExecutionException full) {
            return failed(safeOperation, safeModel, MatrixErrorCode.OVERLOADED, callback);
        }
        return pending;
    }

    private ModelConfig requireExistingConfig(ModelConfigInput input) {
        if (input == null || input.schemaVersion > com.matrix.agent.api.common.ParcelSchema.CURRENT
                || input.providerId == null || !input.providerId.matches("[a-z0-9_]{1,40}")
                || input.apiKeyRef == null || !input.apiKeyRef.equals("host-keystore:" + input.providerId)) {
            throw new IllegalArgumentException("invalid model configuration reference");
        }
        ModelConfig current = models.load();
        if (current == null || !input.providerId.equals(current.providerId)) {
            throw new IllegalArgumentException("provider is not provisioned by host");
        }
        return current;
    }

    private void activateSelectedModel(ModelConfig selected) throws Exception {
        com.matrix.agent.task.ModelGateway gateway = models.createModelGateway(selected);
        com.matrix.agent.task.identity.IntentClassifier classifier =
                models.buildIntentClassifier(selected);
        models.save(selected);
        runtime.setModelGateway(gateway, models.displayName(selected));
        runtime.setIntentClassifier(classifier);
    }

    private ModelConfig configForModel(String modelId) {
        ModelConfig current = models.load();
        if (current != null && modelId.equals(current.model)) return current;
        if (downloads == null || downloads.getByName(modelId) == null) {
            throw new IllegalArgumentException("model is not installed");
        }
        return new ModelConfig("on_device", modelId, ApiProtocol.ON_DEVICE, "", modelId, "", false);
    }

    private static ConnectionTestResult connectionFailure(int code) {
        return new ConnectionTestResult(false, 0L, code, "connection unavailable");
    }
    private static String requireModelId(String modelId) {
        if (modelId == null || !modelId.matches("(?=.{1,120}$)[A-Za-z0-9._:-]+(/[A-Za-z0-9._:-]+)?"))
            throw new IllegalArgumentException("invalid modelId");
        return modelId;
    }
    private static Provisioning requireProvisioning(ModelProvisionInput input) {
        if (input == null || input.schemaVersion > com.matrix.agent.api.common.ParcelSchema.CURRENT
                || input.providerId == null || !input.providerId.matches("[a-z0-9_]{1,40}")) {
            throw new IllegalArgumentException("invalid provisioning input");
        }
        ModelProviderPreset preset = presetFor(input.providerId);
        String suppliedModel = input.modelId == null ? "" : input.modelId.trim();
        if (!suppliedModel.isEmpty() && !suppliedModel.matches("(?=.{1,120}$)[A-Za-z0-9._:-]+(/[A-Za-z0-9._:-]+)?")) {
            throw new IllegalArgumentException("invalid model identifier");
        }
        if ("doubao".equals(preset.id) && suppliedModel.isEmpty()) {
            throw new IllegalArgumentException("Doubao endpoint id is required");
        }
        String model = suppliedModel.isEmpty() ? preset.model : suppliedModel;
        if (model == null || model.trim().isEmpty()) {
            throw new IllegalArgumentException("model identifier is required");
        }
        return new Provisioning(preset, model, resolveEndpoint(preset, input.endpoint));
    }
    private static ModelProviderPreset presetFor(String providerId) {
        for (ModelProviderPreset preset : ModelProviderPreset.all()) {
            if (preset.id.equals(providerId) && preset.protocol != ApiProtocol.ON_DEVICE) return preset;
        }
        throw new IllegalArgumentException("provider is not credential-provisionable");
    }

    private static String resolveEndpoint(ModelProviderPreset preset, String supplied) {
        String override = supplied == null ? "" : supplied.trim();
        boolean allowsOverride = "ollama".equals(preset.id) || "lmstudio".equals(preset.id)
                || "vllm".equals(preset.id) || "custom".equals(preset.id);
        if ("custom".equals(preset.id) && override.isEmpty()) {
            throw new IllegalArgumentException("custom provider endpoint is required");
        }
        if (!override.isEmpty() && !allowsOverride) {
            throw new IllegalArgumentException("endpoint override is not allowed for this provider");
        }
        String endpoint = override.isEmpty() ? preset.endpoint : override;
        if (endpoint == null || endpoint.isEmpty()) {
            throw new IllegalArgumentException("endpoint is required");
        }
        try {
            ModelConfig.validateEndpoint(endpoint, preset.protocol);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("invalid endpoint", invalid);
        }
        return endpoint;
    }

    private static final class Provisioning {
        final ModelProviderPreset provider;
        final String modelId;
        final String endpoint;
        Provisioning(ModelProviderPreset provider, String modelId, String endpoint) {
            this.provider = provider;
            this.modelId = modelId;
            this.endpoint = endpoint;
        }
    }
    private static byte[] readSecret(ParcelFileDescriptor pipe, boolean required) throws Exception {
        final int maxBytes = 8 * 1024;
        try (FileInputStream input = new FileInputStream(pipe.getFileDescriptor());
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[512];
            for (int read; (read = input.read(chunk)) != -1;) {
                if (out.size() + read > maxBytes) throw new IllegalArgumentException("credential too large");
                out.write(chunk, 0, read);
            }
            byte[] value = out.toByteArray();
            if (required && value.length == 0) throw new IllegalArgumentException("credential required");
            return value;
        }
    }
    private static void closeQuietly(ParcelFileDescriptor value) {
        if (value == null) return;
        try { value.close(); } catch (Exception ignored) { }
    }
    private static ModelOperationHandle failed(String op, String model, int code, IModelCallback callback) {
        ModelOperationHandle failed = new ModelOperationHandle(op, model, ModelOperationHandle.STATE_FAILED);
        notifyFinished(callback, failed, code);
        return failed;
    }
    private static void notifyFinished(IModelCallback callback, ModelOperationHandle handle, int code) {
        if (callback == null) return;
        try { callback.onModelOperationFinished(handle, code); } catch (RemoteException ignored) { }
    }
}
