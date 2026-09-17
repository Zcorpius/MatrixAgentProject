package com.matrix.agent.voice;

import com.matrix.agent.voice.port.*;

/**
 * 语音策略与阈值配置。
 *
 * <p>集中管理端点/超时/复述/转写校验/TTS/barge-in 阈值,避免散落在 Controller/CaptureController 硬编码。
 * 默认值取自 docs/voice-assistant-implementation-plan.md §3.7;真机语料出来后调整。
 *
 * <p>{@code maxReprompt} 语义:REJECT 时 {@code repromptCount++},若 {@code <= maxReprompt} 再听一轮。
 *
 * <p>TTS 超时:{@code ttsReadyMs}=冷初始化就绪等待;{@code ttsPerCharMs}/@code ttsMaxSpeakMs}=
 * 单次播报按文本长度估算 {@code min(charCount*ttsPerCharMs, ttsMaxSpeakMs)}。
 *
 * <p>barge-in:{@code bargeInRmsThreshold}=RMS 阈值;{@code bargeInHoldFrames}=超阈值连续帧数
 * (帧计数,非 wall clock);{@code bargeInForceHalfDuplex}=强制半双工(真机回声过大时设 true)。
 */
public final class VoicePolicyConfig {
    private final long speechStartMs;
    private final long maxSpeechMs;
    private final long finalWaitMs;
    private final int maxReprompt;
    private final float minConfidence;
    private final int minTextLength;
    private final long ttsReadyMs;
    private final long ttsPerCharMs;
    private final long ttsMaxSpeakMs;
    private final int bargeInRmsThreshold;
    private final int bargeInHoldFrames;
    private final boolean bargeInForceHalfDuplex;

    /** 6 参构造(向后兼容,TTS + barge-in 字段用默认值)。 */
    public VoicePolicyConfig(long speechStartMs, long maxSpeechMs, long finalWaitMs,
            int maxReprompt, float minConfidence, int minTextLength) {
        this(speechStartMs, maxSpeechMs, finalWaitMs, maxReprompt, minConfidence, minTextLength,
                3000L, 200L, 30000L);
    }

    /** 9 参构造(含 TTS,barge-in 字段用默认值)。 */
    public VoicePolicyConfig(long speechStartMs, long maxSpeechMs, long finalWaitMs,
            int maxReprompt, float minConfidence, int minTextLength,
            long ttsReadyMs, long ttsPerCharMs, long ttsMaxSpeakMs) {
        this(speechStartMs, maxSpeechMs, finalWaitMs, maxReprompt, minConfidence, minTextLength,
                ttsReadyMs, ttsPerCharMs, ttsMaxSpeakMs, 1500, 5, false);
    }

    /** 12 参构造(全字段)。 */
    public VoicePolicyConfig(long speechStartMs, long maxSpeechMs, long finalWaitMs,
            int maxReprompt, float minConfidence, int minTextLength,
            long ttsReadyMs, long ttsPerCharMs, long ttsMaxSpeakMs,
            int bargeInRmsThreshold, int bargeInHoldFrames, boolean bargeInForceHalfDuplex) {
        if (speechStartMs < 0) throw new IllegalArgumentException("speechStartMs 不能为负");
        if (maxSpeechMs < 0) throw new IllegalArgumentException("maxSpeechMs 不能为负");
        if (finalWaitMs < 0) throw new IllegalArgumentException("finalWaitMs 不能为负");
        if (maxReprompt < 0) throw new IllegalArgumentException("maxReprompt 不能为负");
        if (!Float.isFinite(minConfidence) || minConfidence < 0f || minConfidence > 1f) {
            throw new IllegalArgumentException("minConfidence 必须在 0 到 1 之间");
        }
        if (minTextLength < 1) throw new IllegalArgumentException("minTextLength 不能小于 1");
        if (ttsReadyMs < 0) throw new IllegalArgumentException("ttsReadyMs 不能为负");
        if (ttsPerCharMs < 0) throw new IllegalArgumentException("ttsPerCharMs 不能为负");
        if (ttsMaxSpeakMs < 0) throw new IllegalArgumentException("ttsMaxSpeakMs 不能为负");
        if (bargeInRmsThreshold < 0) throw new IllegalArgumentException("bargeInRmsThreshold 不能为负");
        if (bargeInHoldFrames < 1) throw new IllegalArgumentException("bargeInHoldFrames 不能小于 1");
        this.speechStartMs = speechStartMs;
        this.maxSpeechMs = maxSpeechMs;
        this.finalWaitMs = finalWaitMs;
        this.maxReprompt = maxReprompt;
        this.minConfidence = minConfidence;
        this.minTextLength = minTextLength;
        this.ttsReadyMs = ttsReadyMs;
        this.ttsPerCharMs = ttsPerCharMs;
        this.ttsMaxSpeakMs = ttsMaxSpeakMs;
        this.bargeInRmsThreshold = bargeInRmsThreshold;
        this.bargeInHoldFrames = bargeInHoldFrames;
        this.bargeInForceHalfDuplex = bargeInForceHalfDuplex;
    }

    /**
     * 默认策略:wake 后等开口 5s / 最大说话 15s / endpoint 等 final 2s / 复述 1 次 / 置信度 0.5 / 最短 1 字符 /
     * TTS 就绪 3s / 每字 200ms / 播报上限 30s / barge-in RMS 1500 / 连续 5 帧(250ms) / 不强制半双工。
     */
    public static VoicePolicyConfig defaults() {
        return new VoicePolicyConfig(5000L, 15000L, 2000L, 1, 0.5f, 1,
                3000L, 200L, 30000L, 1500, 5, false);
    }

    public long speechStartMs() { return speechStartMs; }
    public long maxSpeechMs() { return maxSpeechMs; }
    public long finalWaitMs() { return finalWaitMs; }
    public int maxReprompt() { return maxReprompt; }
    public float minConfidence() { return minConfidence; }
    public int minTextLength() { return minTextLength; }
    public long ttsReadyMs() { return ttsReadyMs; }
    public long ttsPerCharMs() { return ttsPerCharMs; }
    public long ttsMaxSpeakMs() { return ttsMaxSpeakMs; }
    /** barge-in RMS 阈值(16bit PCM 振幅均方根)。 */
    public int bargeInRmsThreshold() { return bargeInRmsThreshold; }
    /** RMS 超阈值需持续的连续帧数(帧计数,每帧 ~50ms)。 */
    public int bargeInHoldFrames() { return bargeInHoldFrames; }
    /** 强制半双工(true 时 SPEAKING 期间不开放免唤醒打断,真机回声过大用)。 */
    public boolean bargeInForceHalfDuplex() { return bargeInForceHalfDuplex; }
}
