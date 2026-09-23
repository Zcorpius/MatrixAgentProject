package com.matrix.agent.model;

import com.matrix.agent.contract.PlannerMode;

import com.matrix.agent.contract.ModelConfig;

import com.matrix.agent.contract.ApiProtocol;

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
    private static final String KEY_GENERATION = "config_generation";
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
        // configurationGeneration（输入交互增强 I5 §8.2）：每次成功持久化单调 +1——
        // 供 ModelExecutionSnapshot 锁定“本任务用的是哪个配置代际”。
        int nextGeneration = preferences.getInt(KEY_GENERATION, 0) + 1;
        boolean committed = preferences.edit()
                .putString("providerId", config.providerId)
                .putString("displayName", config.displayName)
                .putString("protocol", config.protocol.name())
                .putString("endpoint", config.endpoint)
                .putString("model", config.model)
                .putBoolean("keyRequired", config.apiKeyRequired)
                .putString("plannerMode", config.plannerMode.name())
                .putString("apiKey", encryptedApiKey)
                .putInt(KEY_GENERATION, nextGeneration)
                .commit();
        if (!committed) {
            throw new java.io.IOException("failed to persist encrypted model configuration");
        }
        Log.d(TAG, "[ConfigStore] saved. apiKeyCipherChars=" + encryptedApiKey.length()
                + " generation=" + nextGeneration
                + " allKeys=" + preferences.getAll().keySet());
    }

    /** 当前配置代际（单调递增；0 = 从未保存过配置）。 */
    public int configurationGeneration() {
        return preferences.getInt(KEY_GENERATION, 0);
    }

    /**
     * 非秘密字段的 SHA-256 指纹（I5 §8.2）：provider|model|protocol|端点类别|generation。
     * 不含 API key 与完整 endpoint（只取“云端/局域网/端侧”类别），可安全进入
     * conversation_task_link 与未来脱敏 TaskDetails 投影。
     */
    public String configFingerprint() {
        ModelConfig config = load();
        return fingerprintOf(config == null ? "" : config.providerId,
                config == null ? "" : config.model,
                config == null ? "" : config.protocol.name(),
                config == null ? "" : ModelExecutionSnapshot.endpointCategory(
                        config.protocol, config.endpoint),
                configurationGeneration());
    }

    /** 纯函数形态（JVM 可测）：canonical 输入 → SHA-256 十六进制。 */
    static String fingerprintOf(String providerId, String model, String protocol,
            String endpointCategory, int generation) {
        String canonical = String.join("|",
                nullSafe(providerId), nullSafe(model), nullSafe(protocol),
                nullSafe(endpointCategory), String.valueOf(generation));
        try {
            java.security.MessageDigest digest =
                    java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
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
