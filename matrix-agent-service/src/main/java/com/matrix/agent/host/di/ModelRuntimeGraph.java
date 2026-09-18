package com.matrix.agent.host.di;

import android.content.Context;
import android.util.Log;

import com.matrix.agent.data.memory.MemoryRecaller;
import com.matrix.agent.data.memory.MemoryStore;
import com.matrix.agent.contract.ApiProtocol;
import com.matrix.agent.model.ModelApiClient;
import com.matrix.agent.contract.ModelConfig;
import com.matrix.agent.model.ModelGatewayRepository;
import com.matrix.agent.model.SecureModelConfigStore;
import com.matrix.agent.ondevice.mnn.MnnOnDeviceLlmFactory;
import com.matrix.agent.platform.MatrixHttpClient;
import com.matrix.agent.task.AgentRuntimeRepository;
import com.matrix.agent.intent.FallbackIntentClassifier;
import com.matrix.agent.intent.ClassifierFactory;
import com.matrix.agent.intent.IntentClassifier;
import com.matrix.agent.intent.KeywordIntentClassifier;
import com.matrix.agent.intent.LlmIntentClassifier;

import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Model-domain composition root.
 *
 * <p>Configuration decryption, gateway creation, intent classification and the initial on-device
 * hot-load belong to one lifecycle.  Keeping them here prevents {@link AppContainer} from
 * duplicating model-mode branching across otherwise unrelated task and persistence wiring.
 */
final class ModelRuntimeGraph {
    private static final String TAG = "MatrixAgent";

    private final ModelApiClient client;
    private final SecureModelConfigStore configStore;
    private final ModelGatewayRepository repository;
    private final IntentClassifier intentClassifier;
    private final ModelConfig initialConfig;

    ModelRuntimeGraph(Context appContext, MemoryStore memoryStore,
            MemoryRecaller memoryRecaller, ScheduledExecutorService deferredReleaseScheduler,
            MatrixHttpClient httpClient) {
        client = new ModelApiClient(httpClient.provider());
        configStore = new SecureModelConfigStore(appContext);
        // Decrypt once at startup. Both classification policy and gateway selection use this same
        // immutable snapshot; repository.load() remains the source of truth for later changes.
        initialConfig = configStore.load();
        repository = new ModelGatewayRepository(configStore, client, memoryStore,
                memoryRecaller, appContext, new MnnOnDeviceLlmFactory(deferredReleaseScheduler));
        intentClassifier = ClassifierFactory.build(client, initialConfig);
    }

    ModelApiClient client() { return client; }
    SecureModelConfigStore configStore() { return configStore; }
    ModelGatewayRepository repository() { return repository; }
    IntentClassifier intentClassifier() { return intentClassifier; }

    /** Applies the boot config without making a GB-scale on-device model load block service start. */
    void applyInitialGateway(AgentRuntimeRepository runtime, Executor localModelExecutor) {
        if (initialConfig == null) {
            Log.i(TAG, "[ModelGraph] no saved model config; keeping offline gateway");
            return;
        }
        if (initialConfig.protocol != ApiProtocol.ON_DEVICE) {
            Log.i(TAG, "[ModelGraph] applying saved remote model gateway");
            runtime.setModelGateway(repository.createModelGateway(initialConfig),
                    repository.displayName(initialConfig));
            return;
        }
        final ModelConfig pending = initialConfig;
        Log.i(TAG, "[ModelGraph] asynchronously loading saved on-device model");
        localModelExecutor.execute(() -> {
            try {
                // A concurrent provision/select wins. Recheck under the global configuration
                // lock and then take the narrower model-file lock before native loading; an old
                // boot task can never publish over a newer model choice.
                repository.withModelMutationLock(() -> {
                    repository.withOnDeviceMutationLock(() -> {
                        ModelConfig current = repository.load();
                        if (!sameModel(current, pending)) {
                            Log.i(TAG, "[ModelGraph] saved config changed; dropping stale on-device load");
                            return null;
                        }
                        runtime.setModelGateway(repository.createModelGateway(pending),
                                repository.displayName(pending));
                        return null;
                    });
                    return null;
                });
                Log.i(TAG, "[ModelGraph] on-device model loaded and activated");
            } catch (Exception error) {
                Log.e(TAG, "[ModelGraph] on-device model load failed", error);
            }
        });
    }

    private static boolean sameModel(ModelConfig left, ModelConfig right) {
        return left != null && right != null && left.protocol == right.protocol
                && left.model.equals(right.model);
    }

}
