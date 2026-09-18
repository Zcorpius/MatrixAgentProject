package com.matrix.agent.intent;

/**
 * IntentClassifier 分类结果——read-only / write / unknown + 置信度 + 理由。
 *
 * <p>旧版 {@code IntentClassifier.isReadOnly()} 仅返回 boolean,升级为带置信度的
 * 结构化结果,支持 LLM-based classifier(LlmIntentClassifier)输出模糊判定:
 * <ul>
 *   <li>{@code confidence < 0.7} 视为 unknown,Repository 按写操作保守处理;</li>
 *   <li>{@code confidence ≥ 0.7} 时 readOnly 字段决定 readOnlyHint;</li>
 *   <li>{@code reason} 用于审计 / 日志(单条字符串,避免 PII)。</li>
 * </ul>
 *
 * <p>不可变值对象——{@link KeywordIntentClassifier} confidence=1.0(规则命中)/ 0.5(模糊);
 * {@code LlmIntentClassifier} 由 LLM 返回 + temperature=0 保证一致性。
 */
public final class IntentResult {
    public static final double CONFIDENCE_THRESHOLD = 0.7;

    private final boolean readOnly;
    private final double confidence;
    private final String reason;

    private IntentResult(boolean readOnly, double confidence, String reason) {
        this.readOnly = readOnly;
        this.confidence = confidence;
        this.reason = reason == null ? "" : reason;
    }

    /** 已知只读——confidence=1.0(规则或 LLM 高置信)。 */
    public static IntentResult readOnly(String reason) {
        return new IntentResult(true, 1.0, reason);
    }

    /** 已知写——confidence=1.0。 */
    public static IntentResult write(String reason) {
        return new IntentResult(false, 1.0, reason);
    }

    /**
     * 模糊 / 未知——按写操作保守处理(readOnly=false),confidence 由 caller 指定(< 0.7)。
     */
    public static IntentResult unknown(String reason, double confidence) {
        return new IntentResult(false, Math.max(0.0, Math.min(0.69, confidence)), reason);
    }

    /** 旧版兼容——从 boolean 直接构造,confidence=1.0,reason="legacy"。 */
    public static IntentResult legacy(boolean readOnly) {
        return new IntentResult(readOnly, 1.0, "legacy");
    }

    public boolean isReadOnly() { return readOnly; }

    public double getConfidence() { return confidence; }

    public String getReason() { return reason; }

    /** 高置信判定——confidence ≥ {@link #CONFIDENCE_THRESHOLD}。 */
    public boolean isHighConfidence() {
        return confidence >= CONFIDENCE_THRESHOLD;
    }

    @Override
    public String toString() {
        return "IntentResult{readOnly=" + readOnly
                + ", confidence=" + confidence
                + ", reason=" + reason + "}";
    }
}
