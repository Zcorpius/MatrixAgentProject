package com.matrix.agent.launcher.presentation;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 模型接入页的非敏感表单草稿。
 *
 * <p>Host 是模型配置的权威存储；这里仅保存方便页面恢复的 provider、模型 ID、服务地址和
 * "已配置密钥"标记。API Key 本身绝不进入 Launcher 的磁盘或内存持久化。
 */
final class ModelFormDraftStore {
    private static final String FILE = "matrix_model_form";
    private static final String PROVIDER = "provider";
    private static final String MODEL = "model";
    private static final String ENDPOINT = "endpoint";
    private static final String HAS_KEY = "has_key";

    private final SharedPreferences preferences;

    ModelFormDraftStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    void save(String providerId, String modelId, String endpoint, boolean hasKey) {
        preferences.edit()
                .putString(PROVIDER, providerId)
                .putString(MODEL, modelId)
                .putString(ENDPOINT, endpoint)
                .putBoolean(HAS_KEY, hasKey)
                .apply();
    }

    Draft load() {
        String providerId = preferences.getString(PROVIDER, null);
        if (providerId == null || providerId.isEmpty()) return null;
        return new Draft(providerId, preferences.getString(MODEL, ""),
                preferences.getString(ENDPOINT, ""), preferences.getBoolean(HAS_KEY, false));
    }

    static final class Draft {
        final String providerId;
        final String modelId;
        final String endpoint;
        final boolean hasKey;

        Draft(String providerId, String modelId, String endpoint, boolean hasKey) {
            this.providerId = providerId;
            this.modelId = modelId;
            this.endpoint = endpoint;
            this.hasKey = hasKey;
        }
    }
}
