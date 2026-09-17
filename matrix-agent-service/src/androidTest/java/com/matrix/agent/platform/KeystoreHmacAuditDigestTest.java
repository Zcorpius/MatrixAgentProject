package com.matrix.agent.platform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.security.KeyStore;
import java.util.regex.Pattern;

/**
 * KeystoreHmacAuditDigest 真路径验证——首次在 emulator 上跑通
 * AndroidKeyStore HMAC-SHA-256 派生密钥链路。
 *
 * <p>JVM 测试 ({@code HmacAuditDigestTest}) 通过包私有构造器用 in-memory key 验证算法行为;
 * 本 instrumented 测试用 1 参 {@code (Context)} 构造器,走 AndroidKeyStore lazy 创建 / 复用
 * 别名 {@code matrix_audit_hmac_key} 的真实路径。
 */
@RunWith(AndroidJUnit4.class)
public final class KeystoreHmacAuditDigestTest {
    private static final Pattern HEX16 = Pattern.compile("^[0-9a-f]{16}$");

    @Test
    public void digestStableForSameInputAcrossTwoInstances() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        KeystoreHmacAuditDigest digest1 = new KeystoreHmacAuditDigest(context);
        // 第二个实例从 KeyStore 复用已存在的密钥(同别名),摘要必须一致
        KeystoreHmacAuditDigest digest2 = new KeystoreHmacAuditDigest(context);

        String input = "把主驾温度调到 24 度";
        String out1 = digest1.digest(input);
        String out2 = digest2.digest(input);
        assertTrue("摘要必须是 16 位 hex, 实际=" + out1, HEX16.matcher(out1).matches());
        assertEquals("两个实例(共享 KeyStore 别名)同输入必须相同", out1, out2);
        assertEquals("hmac", digest1.prefix());
        assertEquals("hmac", digest2.prefix());
    }

    @Test
    public void digestDiffersForDifferentInputs() {
        Context context = ApplicationProvider.getApplicationContext();
        KeystoreHmacAuditDigest digest = new KeystoreHmacAuditDigest(context);
        String a = digest.digest("导航回家");
        String b = digest.digest("导航去公司");
        assertNotEquals("不同输入必须产生不同摘要", a, b);
        assertTrue("a 是 16 hex", HEX16.matcher(a).matches());
        assertTrue("b 是 16 hex", HEX16.matcher(b).matches());
    }

    @Test
    public void keyStoreAliasCreatedAfterDigest() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        new KeystoreHmacAuditDigest(context).digest("trigger key creation");

        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        assertTrue("matrix_audit_hmac_key alias 必须在 Keystore 内",
                keyStore.containsAlias("matrix_audit_hmac_key"));
    }
}
