package com.matrix.agent.model;

import android.content.Context;
import android.content.SharedPreferences;
import android.annotation.SuppressLint;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class SecureModelConfigStore {
    private static final String TAG = "MatrixAgent";
    private static final String FILE = "matrix_model_config";
    private static final String KEY_ALIAS = "matrix_agent_api_key";
    private final SharedPreferences preferences;

    public SecureModelConfigStore(Context context) {
        preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        Log.i(TAG, "[ConfigStore] init file=" + FILE
                + " knownKeys=" + preferences.getAll().keySet());
    }

    /**
     * Called only by the Host's bounded I/O executor. A successful Binder completion must mean
     * that the encrypted configuration is already durable, so apply() is deliberately unsuitable.
     */
    @SuppressLint("ApplySharedPref")
    public void save(ModelConfig config) throws Exception {
        Log.i(TAG, "[ConfigStore] save provider=" + config.providerId
                + " model=" + config.model + " mode=" + config.plannerMode
                + " apiKeyChars=" + (config.apiKey == null ? 0 : config.apiKey.length()));
        String encryptedApiKey = encrypt(config.apiKey);
        boolean committed = preferences.edit()
                .putString("providerId", config.providerId)
                .putString("displayName", config.displayName)
                .putString("protocol", config.protocol.name())
                .putString("endpoint", config.endpoint)
                .putString("model", config.model)
                .putBoolean("keyRequired", config.apiKeyRequired)
                .putString("plannerMode", config.plannerMode.name())
                .putString("apiKey", encryptedApiKey)
                .commit();
        if (!committed) {
            throw new java.io.IOException("failed to persist encrypted model configuration");
        }
        Log.d(TAG, "[ConfigStore] saved. apiKeyCipherChars=" + encryptedApiKey.length()
                + " allKeys=" + preferences.getAll().keySet());
    }

    public ModelConfig load() {
        String providerId = preferences.getString("providerId", null);
        if (providerId == null) {
            Log.i(TAG, "[ConfigStore] load -> no saved config (providerId is null)");
            return null;
        }
        Log.d(TAG, "[ConfigStore] load providerId=" + providerId
                + " hasApiKeyCipher=" + preferences.contains("apiKey")
                + " apiKeyCipherChars=" + preferences.getString("apiKey", "").length());
        try {
            ModelConfig config = new ModelConfig(
                    providerId,
                    preferences.getString("displayName", providerId),
                    ApiProtocol.valueOf(preferences.getString("protocol", ApiProtocol.OPENAI_CHAT.name())),
                    preferences.getString("endpoint", ""),
                    preferences.getString("model", ""),
                    decrypt(preferences.getString("apiKey", "")),
                    preferences.getBoolean("keyRequired", true),
                    PlannerMode.valueOf(preferences.getString("plannerMode",
                            PlannerMode.STRUCTURED_JSON_COMPATIBILITY.name())));
            // SharedPreferences is storage, never a trust boundary. Validate decrypted persisted
            // fields before a later startup can route them into HTTP or native model loading.
            config.validate();
            Log.i(TAG, "[ConfigStore] load OK provider=" + config.providerId
                    + " model=" + config.model + " mode=" + config.plannerMode);
            return config;
        } catch (Exception error) {
            Log.e(TAG, "[ConfigStore] load FAILED, returning null. cause="
                    + error.getClass().getSimpleName() + ": " + error.getMessage(), error);
            return null;
        }
    }

    private String encrypt(String plainText) throws Exception {
        if (plainText == null || plainText.isEmpty()) return "";
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "."
                + Base64.encodeToString(encrypted, Base64.NO_WRAP);
    }

    private String decrypt(String value) throws Exception {
        if (value == null || value.isEmpty()) return "";
        String[] parts = value.split("\\.", 2);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(),
                new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        // 显式 256-bit AES,与 AndroidKeyStoreMasterKeyProvider 一致(旧实现依赖 AndroidKeyStore
        // 默认的 128-bit)。向后兼容:上面 containsAlias 为 true 时已提前 return 复用旧 key,
        // 已存配置仍按旧 key 解密,不破坏数据;仅首次生成(全新安装)走 256-bit。
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }
}
