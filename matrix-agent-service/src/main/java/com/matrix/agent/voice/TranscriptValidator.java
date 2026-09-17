package com.matrix.agent.voice;

import com.matrix.agent.voice.port.*;

/**
 * ASR 转写结果校验。
 *
 * <p>Controller 拿到 final(进入 RECOGNIZING)后裁决:空文本 / 过短噪声 / 低置信度 / 语言不一致
 * → REJECT(静默复述或结束),否则 ACCEPT(提交 Agent)。{@code confidenceAvailable=false}(未知置信度)
 * 同样 REJECT(<b>fail-closed</b>:未知不可信,复述一次仍未知则结束);正常转写需 Vosk 解析 word result
 * 产出可用置信度,否则转写层不应放行进 Agent / 车控。
 *
 * <p>fail-closed 倾向:转写不够清楚就复述,不把模糊输入直接送给 Agent / 车控。
 *
 * <p>语言校验:Demo 单中文(VoskAsrAdapter 固定 "zh-CN"),不一致则拒(防引擎/模型错配)。
 *
 * <p><b>不做自然语言身份/音区黑名单</b>:文档 §15「ASR 文本不能声明 actor / audio zone」指身份与输入
 * 音区始终来自可信应用上下文(固定 {@code Actor.DRIVER} / 默认驾驶音区),绝不从转写文本推导——因此
 * 「我是副驾」之类文本天然无法覆盖身份,无需在转写层拦截。命令内容提及目标座位(如「给副驾开空调」)
 * 是合法的能力目标参数,由 capability schema / PolicyEngine / 产品范围裁决(确认 + allowlist),
 * 不应在转写层用关键词黑名单拒绝(会误杀合法车控命令)。
 */
public final class TranscriptValidator {

    public enum Decision { ACCEPT, REJECT }

    public static final class Verdict {
        private final Decision decision;
        private final String reason;

        Verdict(Decision decision, String reason) {
            this.decision = decision;
            this.reason = reason;
        }

        public boolean accepted() { return decision == Decision.ACCEPT; }
        public Decision decision() { return decision; }
        public String reason() { return reason; }
    }

    /** Demo 期望语言(单中文);量产多语言时提升为 policy 配置或注入。 */
    private static final String EXPECTED_LANGUAGE_TAG = "zh-CN";

    /**
     * @param repromptCount 当前会话已复述次数(透传,预留给后续「复述轮提高置信阈值」扩展)。
     */
    public Verdict validate(FinalTranscript transcript, VoicePolicyConfig policy, int repromptCount) {
        if (transcript == null) throw new IllegalArgumentException("transcript 不能为空");
        if (policy == null) throw new IllegalArgumentException("policy 不能为空");
        String text = transcript.text() == null ? "" : transcript.text().trim();
        if (text.isEmpty()) {
            return new Verdict(Decision.REJECT, "EMPTY");
        }
        if (text.length() < policy.minTextLength()) {
            return new Verdict(Decision.REJECT, "TOO_SHORT");
        }
        // fail-closed:未知置信度(confidenceAvailable=false)或低于阈值 → REJECT(复述一次,仍未知则结束)。
        // 不把无置信度依据的转写直接送给 Agent / 写操作。
        if (!transcript.confidenceAvailable() || transcript.confidence() < policy.minConfidence()) {
            return new Verdict(Decision.REJECT, "UNKNOWN_OR_LOW_CONFIDENCE");
        }
        // 语言校验:Demo 单中文,不一致则拒(防引擎/模型错配,如误装英文模型却当中文用)。
        if (!EXPECTED_LANGUAGE_TAG.equalsIgnoreCase(transcript.languageTag())) {
            return new Verdict(Decision.REJECT, "UNSUPPORTED_LANGUAGE");
        }
        return new Verdict(Decision.ACCEPT, "OK");
    }
}
