package com.matrix.agent.platform;

/**
 * KeystoreHmacAuditDigest 装配失败时的 fail-closed digest。
 *
 * <p><b>为何不退回 {@link Sha1AuditDigest}</b>:审计侧自由文本摘要从
 * SHA-1 8 位升级到 HMAC-SHA-256 截断 16 位,根本原因是 SHA-1 8 位对低熵文本(电话号码 /
 * 短地址 / 固定指令)可被枚举比对——攻击者拿到审计 DB 后枚举常见电话号码 SHA-1 前 8 位
 * 反推原文。若 Keystore 异常设备仍退回 SHA-1,等于让那些设备重暴露于已识别的隐私风险。
 *
 * <p><b>fail-closed 而非 fail-degrade</b>:HMAC 失败时审计数据继续 SQLCipher 加密落盘
 * (可用性优先),但摘要字段必须是不可比对的固定值——不暴露任何"同输入同输出"的稳定性
 * 信号,杜绝攻击者用枚举法反推原文。
 *
 * <p><b>诊断权衡</b>:本实现让事后按已知明文比对审计行的能力失效(诊断降级);这是
 * 隐私 vs 可诊断性的明确权衡,在 HMAC 装配失败的边缘场景里偏向隐私。
 *
 * <p>输出格式:{@code [redacted:chars=N,digest=unavailable]}。
 */
public final class UnavailableAuditDigest implements AuditDigest {
    private static final String FIXED_DIGEST = "unavailable";
    private static final String PREFIX = "digest";

    @Override
    public String digest(String input) {
        return FIXED_DIGEST;
    }

    @Override
    public String prefix() {
        return PREFIX;
    }
}
