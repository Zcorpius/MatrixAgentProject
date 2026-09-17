package com.matrix.agent.host;

import android.content.Context;
import android.util.Log;

import com.matrix.agent.data.memory.MemoryRecaller;
import com.matrix.agent.data.memory.MemoryStore;
import com.matrix.agent.model.ApiProtocol;
import com.matrix.agent.model.ModelApiClient;
import com.matrix.agent.model.ModelConfig;
import com.matrix.agent.model.ModelGatewayRepository;
import com.matrix.agent.model.SecureModelConfigStore;
import com.matrix.agent.ondevice.mnn.MnnOnDeviceLlmFactory;
import com.matrix.agent.task.AgentRuntimeRepository;
import com.matrix.agent.task.capability.CapabilityRegistry;
import com.matrix.agent.task.identity.FallbackIntentClassifier;
import com.matrix.agent.task.identity.IntentClassifier;
import com.matrix.agent.task.identity.KeywordIntentClassifier;
import com.matrix.agent.task.identity.LlmIntentClassifier;

import java.util.concurrent.Executor;

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

    ModelRuntimeGraph(Context appContext, CapabilityRegistry registry, MemoryStore memoryStore,
            MemoryRecaller memoryRecaller) {
        client = new ModelApiClient();
        configStore = new SecureModelConfigStore(appContext);
        // Decrypt once at startup. Both classification policy and gateway selection use this same
        // immutable snapshot; repository.load() remains the source of truth for later changes.
        initialConfig = configStore.load();
        repository = new ModelGatewayRepository(configStore, client, registry, memoryStore,
                memoryRecaller, appContext, new MnnOnDeviceLlmFactory());
        intentClassifier = buildIntentClassifier(client, initialConfig);
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
                // A concurrent save wins. Never let an old startup load overwrite the user's
                // newer model selection.
                ModelConfig current = repository.load();
                if (!sameModel(current, pending)) {
                    Log.i(TAG, "[ModelGraph] saved config changed; dropping stale on-device load");
                    return;
                }
                runtime.setModelGateway(repository.createModelGateway(pending),
                        repository.displayName(pending));
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

    private static IntentClassifier buildIntentClassifier(ModelApiClient client, ModelConfig config) {
        if (config == null || config.protocol == ApiProtocol.ON_DEVICE) {
            Log.i(TAG, "[ModelGraph] IntentClassifier = Keyword");
            return KeywordIntentClassifier.INSTANCE;
        }
        try {
            return new FallbackIntentClassifier(new LlmIntentClassifier(client, config),
                    KeywordIntentClassifier.INSTANCE);
        } catch (Exception error) {
            Log.w(TAG, "[ModelGraph] LLM intent classifier unavailable; using Keyword", error);
            return KeywordIntentClassifier.INSTANCE;
        }
    }
}
