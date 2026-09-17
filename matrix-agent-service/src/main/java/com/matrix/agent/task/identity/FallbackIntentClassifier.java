package com.matrix.agent.task.identity;

import android.util.Log;

/**
 * Fallback 意图分类器——包装 LLM + Keyword,Llm 失败 / 超时时退 Keyword。
 *
 * <p>主路径行为:
 * <ol>
 *   <li>调 {@link LlmIntentClassifier#classify};</li>
 *   <li>异常({@code LlmClassificationException} 包装 Provider 异常 / 超时)→ 退 Keyword;</li>
 *   <li>LLM confidence < 0.7 时也退 Keyword(避免低置信判断导致 readOnlyHint 漂移)。</li>
 * </ol>
 *
 * <p>AppContainer 主路径注入 FallbackIntentClassifier;测试可单独注入 Llm / Keyword。
 */
public final class FallbackIntentClassifier implements IntentClassifier {
    private static final String TAG = "MatrixAgent";

    private final IntentClassifier primary;  // 通常是 LlmIntentClassifier
    private final IntentClassifier fallback;  // 通常是 KeywordIntentClassifier

    public FallbackIntentClassifier(IntentClassifier primary, IntentClassifier fallback) {
        if (primary == null) throw new IllegalArgumentException("primary 不能为空");
        if (fallback == null) throw new IllegalArgumentException("fallback 不能为空");
        this.primary = primary;
        this.fallback = fallback;
    }

    @Override
    public IntentResult classify(String command) {
        if (command == null || command.trim().isEmpty()) {
            return IntentResult.unknown("empty-command", 0.0);
        }
        try {
            IntentResult result = primary.classify(command);
            if (result.isHighConfidence()) {
                return result;
            }
            // 低置信——退 Keyword(规则至少给出 deterministic 判定)
            Log.i(TAG, "[FallbackClassifier] primary low-confidence=" + result.getConfidence()
                    + ", fallback to keyword");
            return fallbackWithReason(fallback.classify(command), "primary-low-confidence");
        } catch (LlmIntentClassifier.LlmClassificationException ex) {
            Log.i(TAG, "[FallbackClassifier] primary FAILED, fallback to keyword");
            return fallbackWithReason(fallback.classify(command), "primary-failed");
        } catch (Exception ex) {
            // 任意其他异常(防御性)也走 fallback
            Log.w(TAG, "[FallbackClassifier] primary unexpected exception cause="
                    + ex.getClass().getSimpleName() + ", fallback to keyword");
            return fallbackWithReason(fallback.classify(command), "primary-unexpected");
        }
    }

    private static IntentResult fallbackWithReason(IntentResult fallbackResult, String reason) {
        // 把 fallback reason 改写为带 fallback 标记,审计时可见
        String augmented = reason + ":" + fallbackResult.getReason();
        if (fallbackResult.isReadOnly() && fallbackResult.isHighConfidence()) {
            return IntentResult.readOnly(augmented);
        }
        if (!fallbackResult.isReadOnly() && fallbackResult.isHighConfidence()) {
            return IntentResult.write(augmented);
        }
        return IntentResult.unknown(augmented, fallbackResult.getConfidence());
    }
}
