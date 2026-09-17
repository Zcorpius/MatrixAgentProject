package com.matrix.agent.platform;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AndroidKeyStore 加密的 SQLCipher passphrase 提供者。
 *
 * <p>复用 {@link SecureModelConfigStore} 的加密模式(AES-256-GCM + KeyStore 别名 +
 * GCM IV),仅别名改为 {@value #KEY_ALIAS}。
 *
 * <p>首次启动生成 32 字节随机 passphrase,用 AndroidKeyStore 主密钥加密后落
 * SharedPreferences;后续启动从 SharedPreferences 取密文,主密钥解密。
 *
 * <p>passphrase 不在内存长期缓存——每次 {@link #getPassphrase()} 都重新解密,
 * 防止 passphrase 在内存中暴露给 dump。
 *
 * <p><b>修复</b>:首次生成 passphrase 时 SharedPreferences 用
 * {@link SharedPreferences.Editor#commit()} 同步落盘(替代 apply()),
 * 消除"生成后立即用于打开 DB,但落盘前进程崩溃 → 下次启动重新生成 → 旧审计库不可读"的断电窗口。
 * 常规读取路径不受影响。同时随机 byte[] 转 char[] 后立即 {@link Arrays#fill} 清零,
 * 减少密钥在堆内存驻留时间。
 */
public final class AndroidKeyStoreMasterKeyProvider implements MasterKeyProvider {
    private static final String TAG = "MatrixAgent";
    public static final String KEY_ALIAS = "matrix_db_master_key";
    private static final String PREF_FILE = "matrix_db_master";
    private static final String PREF_KEY = "passphrase_cipher";
    private static final int GCM_TAG_BITS = 128;
    private static final int GCM_IV_BYTES = 12;
    private static final int PASSPHRASE_BYTES = 32;

    private final SharedPreferences preferences;

    public AndroidKeyStoreMasterKeyProvider(Context context) {
        this.preferences = context.getApplicationContext()
                .getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
    }

    @Override
    public char[] getPassphrase() {
        try {
            String cipherText = preferences.getString(PREF_KEY, null);
            if (cipherText == null || cipherText.isEmpty()) {
                // 首次生成路径:必须同步落盘(commit),否则进程在 apply() 异步刷盘前崩溃,
                // 下次启动会重新生成新 passphrase,旧 SQLCipher 库将不可读——审计数据丢失。
                byte[] fresh = generateRandomPassphrase();
                try {
                    String encoded = encryptToBase64(fresh);
                    boolean committed = preferences.edit().putString(PREF_KEY, encoded).commit();
                    if (!committed) {
                        throw new IllegalStateException("SharedPreferences.commit() returned false");
                    }
                    Log.i(TAG, "[MasterKeyProvider] init alias=" + KEY_ALIAS
                            + " created=true committed=true");
                    return toCharsAndClear(fresh);
                } finally {
                    // 加密 + 落盘完成后,raw 随机字节不再需要——立即清零减少堆驻留。
                    Arrays.fill(fresh, (byte) 0);
                }
            }
            byte[] plain = decryptFromBase64(cipherText);
            try {
                Log.i(TAG, "[MasterKeyProvider] init alias=" + KEY_ALIAS + " created=false");
                return toCharsAndClear(plain);
            } finally {
                Arrays.fill(plain, (byte) 0);
            }
        } catch (Exception ex) {
            // KeyStore / 加解密失败必须抛异常,
            // 让 MatrixDatabase.getInstance 把 IllegalStateException 透传给 AppContainer,
            // 由 AuditRuntimeGraph 退化 NoopAuditRepository——绝不返回 null
            // 触发"明文 Room DB"路径。审计数据不能悄然落为明文。
            Log.e(TAG, "[MasterKeyProvider] getPassphrase FAILED cause="
                    + ex.getClass().getSimpleName() + ": " + ex.getMessage(), ex);
            throw new IllegalStateException("MasterKeyProvider getPassphrase failed", ex);
        }
    }

    @Override
    public String alias() {
        return KEY_ALIAS;
    }

    private static byte[] generateRandomPassphrase() {
        byte[] bytes = new byte[PASSPHRASE_BYTES];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    private String encryptToBase64(byte[] plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] cipherBytes = cipher.doFinal(plain);
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + "."
                + Base64.encodeToString(cipherBytes, Base64.NO_WRAP);
    }

    private byte[] decryptFromBase64(String value) throws Exception {
        String[] parts = value.split("\\.", 2);
        if (parts.length != 2) throw new IllegalArgumentException("密文格式错误");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(),
                new GCMParameterSpec(GCM_TAG_BITS, Base64.decode(parts[0], Base64.NO_WRAP)));
        return cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP));
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) keyStore.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(GCM_TAG_BITS * 2)  // 256-bit AES
                .build());
        return generator.generateKey();
    }

    /**
     * 把 32 字节随机按 ISO-8859-1 转 char[](避免 UTF-8 多字节损坏),
     * 同时立即 {@link Arrays#fill} 清零调用方传入的 byte[]。
     *
     * <p>调用方在 finally 块里会再次 fill(防御性),本方法的 fill 是为了
     * 让 try 块中后续逻辑(如 commit 失败抛异常)不会泄漏原 byte[]。
     */
    private static char[] toCharsAndClear(byte[] bytes) {
        char[] chars = new char[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            chars[i] = (char) (bytes[i] & 0xFF);
        }
        Arrays.fill(bytes, (byte) 0);
        return chars;
    }
}
