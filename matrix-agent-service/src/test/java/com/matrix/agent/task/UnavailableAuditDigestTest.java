package com.matrix.agent.task;

import com.matrix.agent.platform.AuditDigest;
import com.matrix.agent.platform.Sha1AuditDigest;
import com.matrix.agent.platform.UnavailableAuditDigest;
import com.matrix.agent.task.redact.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.regex.Pattern;

/**
 * 验证 {@link UnavailableAuditDigest} fail-closed 语义。
 *
 * <p>核心断言:
 * <ul>
 *   <li>{@code digest()} 对任何输入(含 null)返回固定 "unavailable",不暴露输入特征。</li>
 *   <li>{@code prefix()} 返回 "digest",最终格式 {@code [redacted:chars=N,digest=unavailable]}。</li>
 *   <li>两个不同输入产出**完全相同**的 digest 字符串(稳定性=0,不可枚举)。</li>
 *   <li>注入 {@link AuditRedactor} 后,redactFreeText 输出符合 fail-closed 格式。</li>
 * </ul>
 *
 * <p>对照 {@code HmacAuditDigestTest}:HMAC 路径同样输入产出相同摘要(稳定性,可事后比对);
 * UnavailableAuditDigest 路径任何输入产出相同摘要(不可比对)。前者是诊断能力,后者是
 * 隐私 fail-closed 兜底。
 */
public final class UnavailableAuditDigestTest {
    private static final Pattern UNAVAILABLE_FORMAT = Pattern.compile(
            "^\\[redacted:chars=(\\d+),digest=unavailable\\]$");

    @Test
    public void digestReturnsFixedUnavailableForAnyInput() {
        UnavailableAuditDigest digest = new UnavailableAuditDigest();
        assertEquals("unavailable", digest.digest("anything"));
        assertEquals("unavailable", digest.digest(""));
        assertEquals("unavailable", digest.digest(null));
        assertEquals("unavailable", digest.digest("把主驾温度调到 24 度"));
    }

    @Test
    public void prefixReturnsDigest() {
        assertEquals("digest", new UnavailableAuditDigest().prefix());
    }

    @Test
    public void differentInputsReturnIdenticalDigest() {
        // 不可枚举的核心保证:不同输入产出相同输出
        UnavailableAuditDigest digest = new UnavailableAuditDigest();
        String a = digest.digest("电话 13800001234");
        String b = digest.digest("地址 幸福路 88 号");
        String c = digest.digest(" totally different English text ");
        assertEquals("任何输入必须产出相同摘要", a, b);
        assertEquals("任何输入必须产出相同摘要", a, c);
    }

    @Test
    public void redactFreeTextWithUnavailableDigestMatchesFailClosedFormat() {
        AuditRedactor redactor = new AuditRedactor(4096);
        redactor.setDigest(new UnavailableAuditDigest());
        String input = "您家庭地址是北京市朝阳区幸福路 88 号,电话 13800001234";
        String result = redactor.redactFreeText(input);

        assertTrue("fail-closed 格式必须匹配 [redacted:chars=N,digest=unavailable], 实际=" + result,
                UNAVAILABLE_FORMAT.matcher(result).matches());
        assertFalse("不应泄露地址", result.contains("幸福路"));
        assertFalse("不应泄露电话", result.contains("13800001234"));
    }

    @Test
    public void redactFreeTextNullWithUnavailableDigest() {
        AuditRedactor redactor = new AuditRedactor(4096);
        redactor.setDigest(new UnavailableAuditDigest());
        // null 输入仍走 AuditRedactor.redactFreeText 的 null 短路,返回空串
        assertEquals("", redactor.redactFreeText(null));
        // 空串输入返回标准 fail-closed 格式(chars=0)
        String emptyResult = redactor.redactFreeText("");
        assertTrue("空串也走 fail-closed 格式",
                UNAVAILABLE_FORMAT.matcher(emptyResult).matches());
    }
}
