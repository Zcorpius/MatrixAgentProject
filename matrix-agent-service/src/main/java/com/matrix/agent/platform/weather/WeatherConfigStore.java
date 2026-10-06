package com.matrix.agent.platform.weather;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Host-only encrypted credential storage. No default host or credential is shipped in an APK. */
public final class WeatherConfigStore {
    private static final String FILE = "matrix_weather_provider";
    private static final String KEY_ALIAS = "matrix_weather_auth_v1";
    public record Config(String host, String authKind, String secret, String keyId, String developerId, String projectId) {
        public Config(String host, String authKind, String secret) { this(host, authKind, secret, "", "", ""); }
        public Config {
            if (host == null || !host.matches("[a-z0-9-]{1,63}\\.qweatherapi\\.com")
                    || !java.util.Set.of("API_KEY", "JWT").contains(authKind)
                    || secret == null || secret.isBlank() || secret.length() > 8192
                    || authKind.equals("API_KEY") && (secret.contains("\n") || secret.contains("\r"))
                    || authKind.equals("JWT") && (!keyId.matches("[A-Za-z0-9_-]{1,64}")
                            || !developerId.matches("[A-Za-z0-9_-]{1,64}")
                            || !projectId.matches("[A-Za-z0-9_-]{1,64}")))
                throw new IllegalArgumentException("无效的天气服务配置");
        }
        public String headerName() { return authKind.equals("JWT") ? "Authorization" : "X-QW-Api-Key"; }
        public String headerValue(long nowSeconds) throws WeatherFailure { return authKind.equals("JWT")
                ? "Bearer " + QWeatherJwtSigner.sign(keyId, developerId, projectId, secret, nowSeconds) : secret; }
    }
    private final SharedPreferences preferences;
    public WeatherConfigStore(Context context) { preferences = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE); }

    @android.annotation.SuppressLint("ApplySharedPref")
    public synchronized void save(Config config) throws Exception {
        if (config.authKind().equals("JWT")) config.headerValue(System.currentTimeMillis() / 1000);
        String cipher = encrypt(config.secret());
        if (!preferences.edit().putString("host", config.host()).putString("authKind", config.authKind())
                .putString("credential", cipher).putString("keyId", config.keyId())
                .putString("developerId", config.developerId()).putString("projectId", config.projectId())
                .commit()) throw new java.io.IOException("天气配置保存失败");
    }
    public synchronized Config load() {
        String host = preferences.getString("host", ""), kind = preferences.getString("authKind", "");
        if (host.isEmpty()) return null;
        try { return new Config(host, kind, decrypt(preferences.getString("credential", "")),
                preferences.getString("keyId", ""), preferences.getString("developerId", ""), preferences.getString("projectId", "")); }
        catch (Exception invalid) { return null; }
    }
    @android.annotation.SuppressLint("ApplySharedPref")
    public synchronized void clear() { preferences.edit().clear().commit(); }

    private static String encrypt(String secret) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "."
                + Base64.encodeToString(cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }
    private static String decrypt(String value) throws Exception {
        String[] parts = value.split("\\.", 2);
        if (parts.length != 2) throw new IllegalArgumentException("invalid encrypted config");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }
    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(KEY_ALIAS)) return ((KeyStore.SecretKeyEntry) store.getEntry(KEY_ALIAS, null)).getSecretKey();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
        return generator.generateKey();
    }
}
