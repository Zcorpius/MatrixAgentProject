package com.matrix.agent.voice.tencent;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import com.matrix.agent.api.voice.TencentTtsConfig;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Dedicated credential boundary for Tencent TTS.
 *
 * <p>The preferences file contains AES-GCM ciphertext only. The unrelated model-provider key
 * alias is deliberately not reused: clearing/changing speech credentials cannot affect LLM
 * access, and a compromise of one configuration record does not make the other decryptable.</p>
 */
public final class SecureTencentTtsConfigStore {
    private static final String FILE = "matrix_tencent_tts";
    private static final String ALIAS = "matrix_agent_tencent_tts_v1";
    private static final String CREDENTIALS = "credentials";
    private final SharedPreferences preferences;

    public SecureTencentTtsConfigStore(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public TencentTtsConfig projection() {
        return new TencentTtsConfig(preferences.contains(CREDENTIALS),
                preferences.getInt("voiceType", TencentTtsConfig.DEFAULT_VOICE_TYPE),
                preferences.getString("emotion", TencentTtsConfig.DEFAULT_EMOTION),
                preferences.getInt("emotionIntensity", TencentTtsConfig.DEFAULT_EMOTION_INTENSITY));
    }

    /** Loads secrets only inside the Host process immediately before TC3 request signing. */
    public Credentials loadCredentials() throws Exception {
        String encrypted = preferences.getString(CREDENTIALS, null);
        if (encrypted == null || encrypted.isEmpty()) return null;
        byte[] plain = decrypt(encrypted);
        try {
            int split = -1;
            for (int i = 0; i < plain.length; i++) {
                if (plain[i] == 0) { split = i; break; }
            }
            if (split <= 0 || split == plain.length - 1) throw new IllegalStateException("invalid credential record");
            String id = new String(plain, 0, split, StandardCharsets.UTF_8);
            String key = new String(plain, split + 1, plain.length - split - 1, StandardCharsets.UTF_8);
            validateCredentials(id, key);
            return new Credentials(id, key);
        } finally {
            java.util.Arrays.fill(plain, (byte) 0);
        }
    }

    @SuppressLint("ApplySharedPref")
    public void save(byte[] secretId, byte[] secretKey, int voiceType, String emotion,
            int intensity) throws Exception {
        validateOptions(voiceType, emotion, intensity);
        validateCredentialBytes(secretId, 16, 128, false);
        validateCredentialBytes(secretKey, 16, 256, true);
        byte[] joined = new byte[secretId.length + 1 + secretKey.length];
        try {
            System.arraycopy(secretId, 0, joined, 0, secretId.length);
            joined[secretId.length] = 0;
            System.arraycopy(secretKey, 0, joined, secretId.length + 1, secretKey.length);
            String cipher = encrypt(joined);
            if (!preferences.edit().putString(CREDENTIALS, cipher).putInt("voiceType", voiceType)
                    .putString("emotion", emotion).putInt("emotionIntensity", intensity).commit()) {
                throw new java.io.IOException("failed to persist Tencent TTS configuration");
            }
        } finally {
            java.util.Arrays.fill(joined, (byte) 0);
        }
    }

    @SuppressLint("ApplySharedPref")
    public void clear() throws Exception {
        if (!preferences.edit().clear().commit()) {
            throw new java.io.IOException("failed to clear Tencent TTS configuration");
        }
    }

    public static void validateOptions(int voiceType, String emotion, int intensity) {
        if (voiceType <= 0 || voiceType > 9_999_999) throw new IllegalArgumentException("invalid voiceType");
        if (emotion == null || !emotion.matches("[a-z_]{1,32}")) {
            throw new IllegalArgumentException("invalid emotion");
        }
        if (intensity < 50 || intensity > 200) throw new IllegalArgumentException("invalid emotion intensity");
    }

    private static void validateCredentials(String id, String key) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{16,128}")) {
            throw new IllegalArgumentException("invalid Tencent SecretId");
        }
        if (key == null || !key.matches("[A-Za-z0-9+/=_-]{16,256}")) {
            throw new IllegalArgumentException("invalid Tencent SecretKey");
        }
    }

    /** Tencent API credentials are ASCII tokens; validate bytes without materialising a secret String. */
    private static void validateCredentialBytes(byte[] value, int min, int max, boolean allowBase64) {
        if (value == null || value.length < min || value.length > max) {
            throw new IllegalArgumentException("invalid Tencent credential length");
        }
        for (byte raw : value) {
            int c = raw & 0xff;
            boolean alphaNum = c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z'
                    || c >= '0' && c <= '9';
            boolean allowed = alphaNum || c == '_' || c == '-'
                    || (allowBase64 && (c == '+' || c == '/' || c == '='));
            if (!allowed) throw new IllegalArgumentException("invalid Tencent credential character");
        }
    }

    private String encrypt(byte[] plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] encrypted = cipher.doFinal(plain);
        try {
            return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "."
                    + Base64.encodeToString(encrypted, Base64.NO_WRAP);
        } finally {
            java.util.Arrays.fill(encrypted, (byte) 0);
        }
    }

    private byte[] decrypt(String stored) throws Exception {
        String[] parts = stored.split("\\.", 2);
        if (parts.length != 2) throw new IllegalStateException("invalid encrypted credential");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128,
                Base64.decode(parts[0], Base64.NO_WRAP)));
        return cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP));
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) {
            return ((KeyStore.SecretKeyEntry) store.getEntry(ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    public static final class Credentials {
        public final String secretId;
        public final String secretKey;
        Credentials(String secretId, String secretKey) { this.secretId = secretId; this.secretKey = secretKey; }
    }
}
