package com.matrix.agent.task;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * 旧版兼容底座——SHA-1 前 8 位 hex。
 *
 * <p>已知限制(注释已登记):SHA-1 8 位对低熵文本(电话号码 /
 * 固定指令 / 短地址)可被枚举比对——攻击者拿到审计 DB 后,枚举常见电话号码计算
 * SHA-1 前 8 位与审计 sha 字段比对,反推原文。
 *
 * <p>本类保留作:
 * <ul>
 *   <li>{@link AuditRedactor} 默认 digest(未注入 {@link AuditDigest} 时),
 *       JVM 测试 ({@code AuditFreeTextRedactionTest}) 零回归。</li>
 * </ul>
 *
 * <p><b>重要变更</b>:生产路径不再使用本类作为 HMAC 装配失败的
 * 退化方案——评审指出 SHA-1 8 位低熵枚举正是要修的风险,HMAC 失败时退回
 * SHA-1 等于让 Keystore 异常设备重暴露。生产路径 HMAC 装配失败改退回
 * {@link UnavailableAuditDigest}(fail-closed 不可比对)。本类仅在未注入路径
 * (JVM 单元测试)继续作旧版行为底座。
 */
public final class Sha1AuditDigest implements AuditDigest {
    @Override
    public String digest(String input) {
        if (input == null) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digestBytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digestBytes.length * 2);
            for (byte b : digestBytes) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
                if (hex.length() >= 8) break;
            }
            return hex.substring(0, Math.min(8, hex.length()));
        } catch (NoSuchAlgorithmException error) {
            // SHA-1 是 JRE 内置算法,理论不会缺失;兜底返回固定标记
            return "no-sha1";
        }
    }

    @Override
    public String prefix() {
        return "sha";
    }
}
