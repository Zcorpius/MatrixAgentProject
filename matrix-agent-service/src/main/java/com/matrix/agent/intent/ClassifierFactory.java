package com.matrix.agent.intent;

import android.util.Log;

import com.matrix.agent.contract.ApiProtocol;
import com.matrix.agent.contract.LlmClient;
import com.matrix.agent.contract.ModelConfig;

/** Centralizes the policy used to select an intent classifier for a model configuration. */
public final class ClassifierFactory {
    private static final String TAG = "IntentClassifier";

    private ClassifierFactory() {
    }

    /** Cloud models use LLM plus keyword fallback; offline/on-device paths use keyword only. */
    public static IntentClassifier build(LlmClient client, ModelConfig config) {
        if (config == null || config.protocol == ApiProtocol.ON_DEVICE) {
            Log.i(TAG, "IntentClassifier = Keyword (offline/on-device)");
            return KeywordIntentClassifier.INSTANCE;
        }
        try {
            return new FallbackIntentClassifier(new LlmIntentClassifier(client, config),
                    KeywordIntentClassifier.INSTANCE);
        } catch (Exception error) {
            Log.w(TAG, "LLM intent classifier unavailable; using Keyword", error);
            return KeywordIntentClassifier.INSTANCE;
        }
    }

    public static IntentClassifier keyword() {
        return KeywordIntentClassifier.INSTANCE;
    }
}
