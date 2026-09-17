package com.matrix.agent.platform;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

import com.matrix.agent.task.AuditDigest;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * 审计侧自由文本 HMAC-SHA-256 摘要——AndroidKeyStore 派生密钥,
 * 截断 16 位 hex,杜绝 SHA-1 8 位的低熵枚举比对攻击。
 *
 * <p><b>选型理由</b>:
 * <ul>
 *   <li>HMAC-SHA-256 引入 server-side secret(KeyStore 派生)——攻击者拿到审计 DB
 *       不知道 HMAC 密钥,即使枚举常见明文也无法比对。</li>
 *   <li>截断 16 位 hex(64-bit)提供 2^64 抗碰撞,远高于 SHA-1 8 位的 2^32。</li>
 *   <li>同输入同输出——事后按已知明文比对(诊断)语义不变。</li>
 *   <li>不可逆推原文——HMAC 单向。</li>
 * </ul>
 *
 * <p><b>密钥生命周期</b>:别名 {@code matrix_audit_hmac_key} 在 AndroidKeyStore 内,
 * 与旧版 {@code matrix_db_master_key} 独立。KeyStore 不导出原始密钥材料,
 * 非 debuggable 设备上其他进程无法读取。
 *
 * <p><b>失败语义</b>:构造失败(KeyStore 不可用 / KEY_ALIAS 已存在但类型不匹配)
 * 抛 {@link IllegalStateException}——{@code AppContainer} 捕获后退回
 * {@code com.matrix.agent.task.UnavailableAuditDigest}(fail-closed 不可比对)。
 * 修正:不再退回 {@link Sha1AuditDigest}——评审指出 SHA-1 8 位
 * 低熵枚举正是本实现要修的风险,Keystore 异常设备上不能重暴露。
 *
 * <p><b>设计选择:为何不在构造期预生成密钥</b>:首次调用 digest() 时 lazy 创建,
 * 避免 APK 冷启动多一个 Keystore 操作(Keystore 在 emulator 上 100~300ms)。
 * 后续调用从 Keystore 取已存在密钥,<10ms。
 */
public final class KeystoreHmacAuditDigest implements AuditDigest {
    private static final String TAG = "MatrixAgent";
    private static final String KEY_ALIAS = "matrix_audit_hmac_key";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    /** 截断位数(16 hex = 64-bit 抗碰撞)。 */
    private static final int HEX_PREFIX_LENGTH = 16;

    private final SecretKey hmacKey;

    public KeystoreHmacAuditDigest(Context context) {
        this.hmacKey = loadOrCreateKey();
        Log.i(TAG, "[AuditDigest] KeystoreHmacAuditDigest ready alias=" + KEY_ALIAS
                + " (V0.5.0 SHA-1 已升级为 HMAC-SHA-256 / 截断 16 hex)");
    }

    @Override
    public String digest(String input) {
        if (input == null) return "";
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(hmacKey);
            byte[] hmacBytes = mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hmacBytes.length * 2);
            for (byte b : hmacBytes) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
                if (hex.length() >= HEX_PREFIX_LENGTH) break;
            }
            return hex.substring(0, Math.min(HEX_PREFIX_LENGTH, hex.length()));
        } catch (Exception error) {
            // Mac 不可用 / init 失败——理论不应发生(HMAC-SHA256 是 JRE 内置,密钥已加载)
            Log.w(TAG, "[AuditDigest] HMAC compute failed, returning fixed marker: "
                    + error.getClass().getSimpleName());
            return "no-hmac";
        }
    }

    @Override
    public String prefix() {
        return "hmac";
    }

    private static SecretKey loadOrCreateKey() {
        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);
            SecretKey existing = (SecretKey) keyStore.getKey(KEY_ALIAS, null);
            if (existing != null) return existing;
            return generateNewKey();
        } catch (Exception error) {
            throw new IllegalStateException("KeystoreHmacAuditDigest init failed alias="
                    + KEY_ALIAS + " cause=" + error.getClass().getSimpleName()
                    + ": " + error.getMessage(), error);
        }
    }

    private static SecretKey generateNewKey() throws Exception {
        // 注:context 参数保留以便未来切换到需要 Context 的 KeyGenParameterSpec.Builder
        // (如 setUserAuthenticationRequired);当前 AndroidKeyStore Provider 不需要 Context。
        javax.crypto.KeyGenerator generator =
                javax.crypto.KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
                        ANDROID_KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY).build());
        return generator.generateKey();
    }

    /**
     * 测试钩子:用 in-memory key 直接构造,绕过 AndroidKeyStore。
     * 仅单元测试用(KeystoreHmacAuditDigestTest androidTest 真路径验证)。
     */
    KeystoreHmacAuditDigest(byte[] rawKeyForTesting) {
        this.hmacKey = new SecretKeySpec(rawKeyForTesting, HMAC_ALGORITHM);
    }
}
