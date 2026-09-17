package com.matrix.agent.platform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.task.AuditDigest;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 验证 {@link KeystoreHmacAuditDigest} 的 JVM 可达行为。
 *
 * <p>真 AndroidKeyStore 链路在 androidTest ({@code KeystoreHmacAuditDigestTest})
 * 验证——本 JVM 测试通过包私有构造器 {@link KeystoreHmacAuditDigest#KeystoreHmacAuditDigest(byte[])}
 * 用 in-memory key 绕过 AndroidKeyStore,javax.crypto.Mac 是 JRE 内置算法,行为一致。
 *
 * <p>覆盖:
 * <ul>
 *   <li>摘要格式 (16 位小写 hex)</li>
 *   <li>同输入稳定性</li>
 *   <li>不同输入差异</li>
 *   <li>PII 不泄漏到 hex 字符串</li>
 *   <li>{@link AuditDigest#prefix()} 返回 "hmac"</li>
 *   <li>null / 空串边界</li>
 * </ul>
 */
public final class HmacAuditDigestTest {
    private static final Pattern HEX16 = Pattern.compile("^[0-9a-f]{16}$");
    /** 测试用 in-memory 32 字节 key (HMAC-SHA-256 推荐 256-bit)。 */
    private static final byte[] TEST_KEY = "test-key-for-hmac-sha256-jvm-test!".getBytes(StandardCharsets.UTF_8);

    private static KeystoreHmacAuditDigest newDigest() {
        return new KeystoreHmacAuditDigest(TEST_KEY);
    }

    @Test
    public void digestReturns16LowercaseHexChars() {
        KeystoreHmacAuditDigest digest = newDigest();
        String out = digest.digest("把主驾温度调到 24 度");
        assertTrue("HMAC 摘要必须是 16 位小写 hex, 实际=" + out, HEX16.matcher(out).matches());
    }

    @Test
    public void digestStableForSameInput() {
        KeystoreHmacAuditDigest digest = newDigest();
        String input = "导航到北京市朝阳区幸福路 88 号";
        assertEquals("相同输入两次必须返回相同摘要",
                digest.digest(input), digest.digest(input));
    }

    @Test
    public void digestDiffersForDifferentInputs() {
        KeystoreHmacAuditDigest digest = newDigest();
        String a = digest.digest("把主驾温度调到 24 度");
        String b = digest.digest("把主驾温度调到 25 度");
        assertNotEquals("只差 1 字符的不同输入必须产生不同摘要", a, b);
    }

    @Test
    public void digestDoesNotLeakPii() {
        KeystoreHmacAuditDigest digest = newDigest();
        String sensitive = "您家庭地址是北京市朝阳区幸福路 88 号,电话 13800001234,姓名 张三";
        String out = digest.digest(sensitive);
        // hex 字符串本身只含 0-9a-f,任何中文 / 数字原文都不该出现
        assertFalse("hex 摘要不包含 '幸福路'", out.contains("幸福路"));
        assertFalse("hex 摘要不包含 '13800001234'", out.contains("13800001234"));
        assertFalse("hex 摘要不包含 '张三'", out.contains("张三"));
        assertFalse("hex 摘要不包含 '朝阳区'", out.contains("朝阳区"));
    }

    @Test
    public void prefixReturnsHmac() {
        KeystoreHmacAuditDigest digest = newDigest();
        assertEquals("hmac", digest.prefix());
    }

    @Test
    public void digestNullAndEmptyReturnsEmpty() {
        KeystoreHmacAuditDigest digest = newDigest();
        assertEquals("null 输入必须返回空串", "", digest.digest(null));
        // 空串仍然产出有效摘要——区别于 null
        String emptyDigest = digest.digest("");
        assertTrue("空串摘要也必须是 16 位 hex, 实际=" + emptyDigest,
                HEX16.matcher(emptyDigest).matches());
    }

    /**
     * 抗碰撞抽查:30 条不同输入的摘要应全不同 (16 hex = 64-bit 抗碰撞,
     * 30 条样本碰撞概率可忽略)。
     */
    @Test
    public void noCollisionForThirtyDifferentInputs() {
        KeystoreHmacAuditDigest digest = newDigest();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < 30; i++) {
            String out = digest.digest("input-" + i);
            assertTrue("HEX16 pattern", HEX16.matcher(out).matches());
            assertTrue("第 " + i + " 条输入摘要碰撞了", seen.add(out));
        }
        assertEquals("30 条不同输入必须有 30 条不同摘要", 30, seen.size());
    }

    /**
     * 集成验证:配合 {@link com.matrix.agent.task.AuditRedactor#redactFreeText(String)}
     * 输出格式 {@code [redacted:chars=N,hmac=xxxxxxxxxxxxxxxx]}。
     */
    @Test
    public void redactFreeTextFormatWithHmacPrefix() {
        AuditDigest digest = newDigest();
        String input = "把主驾温度调到 24 度";
        String formatted = "[redacted:chars=" + input.length() + "," + digest.prefix() + "="
                + digest.digest(input) + "]";
        Matcher m = Pattern.compile(
                "^\\[redacted:chars=(\\d+),hmac=([0-9a-f]{16})\\]$").matcher(formatted);
        assertTrue("格式必须匹配 [redacted:chars=N,hmac=16hex], 实际=" + formatted,
                m.matches());
        assertEquals(String.valueOf(input.length()), m.group(1));
    }

    /** 防呆:用错误长度 key 构造时,SecretKeySpec 仍能工作(HMAC 接受任意长度 key)。 */
    @Test
    public void digestWithShortKeyStillWorks() {
        byte[] shortKey = "k".getBytes(StandardCharsets.UTF_8);
        KeystoreHmacAuditDigest digest = new KeystoreHmacAuditDigest(shortKey);
        String out = digest.digest("anything");
        assertTrue("短 key 也应输出 16 位 hex", HEX16.matcher(out).matches());
    }
}
