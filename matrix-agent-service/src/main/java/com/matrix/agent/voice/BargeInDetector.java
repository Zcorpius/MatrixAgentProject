package com.matrix.agent.voice;

import com.matrix.agent.voice.port.*;

/**
 * 播报期 barge-in 检测器(纯 Java,无 Android 依赖,JVM 可测)。
 *
 * <p>消费 PCM 帧,RMS 超阈值连续 {@code holdFrames} 帧 → 触发 {@link Callback#onBargeIn()}。
 * AEC 不可用或 {@link VoicePolicyConfig#bargeInForceHalfDuplex()} 时降级<strong>半双工</strong>
 * (不触发,让用户用 UI cancel),避免 TTS 自身回声稳定自打断。
 *
 * <p><b>帧计数而非 wall clock</b>:每帧 ~50ms 固定,确定性强、不依赖可改系统时钟、JVM 可测
 * (满足文档 §3.7 不用 wall clock 的约束)。VAD speech 留待后续(Vosk acceptWaveForm 不提供 speech start)。
 *
 * <p>线程模型:{@link #feed} 在采音线程串行调用;{@link #setAecAvailable} 由 start 线程调一次;
 * {@code aecAvailable} volatile 保证可见,{@code loudFrames} 仅 feed 单线程读写。
 */
public final class BargeInDetector {

    /** barge-in 触发回调(经上层 runInState 落状态机线程)。 */
    public interface Callback {
        void onBargeIn();
    }

    private final VoicePolicyConfig policy;
    private final Callback callback;
    private volatile boolean aecAvailable = false;
    private int loudFrames = 0;

    public BargeInDetector(VoicePolicyConfig policy, Callback callback) {
        if (policy == null) throw new IllegalArgumentException("policy 不能为空");
        if (callback == null) throw new IllegalArgumentException("callback 不能为空");
        this.policy = policy;
        this.callback = callback;
    }

    /** AEC 是否成功启用(VoiceCaptureController.start 检测后调)。false → 半双工不触发。 */
    public void setAecAvailable(boolean available) {
        this.aecAvailable = available;
    }

    /**
     * 处理一帧 PCM(16kHz mono 16bit LE)。仅在 SPEAKING 态由 captureLoop 调用。
     */
    public void feed(byte[] pcm, int len) {
        if (pcm == null || len <= 0) return;
        if (!aecAvailable || policy.bargeInForceHalfDuplex()) {
            loudFrames = 0; // 半双工:不检测,清零
            return;
        }
        if (rms(pcm, len) > policy.bargeInRmsThreshold()) {
            loudFrames++;
            if (loudFrames >= policy.bargeInHoldFrames()) {
                loudFrames = 0; // 触发后清零,不连续触发
                callback.onBargeIn();
            }
        } else {
            loudFrames = 0; // 回落立即复位
        }
    }

    /** 清零连续帧计数(captureLoop 状态变化时调,防跨 SPEAKING 残留误触发)。 */
    public void reset() {
        loudFrames = 0;
    }

    /** 16bit PCM 的 RMS 振幅均方根(迁自 VoiceCaptureController)。 */
    private static double rms(byte[] pcm, int len) {
        long sum = 0;
        int samples = len / 2;
        for (int i = 0; i + 1 < len; i += 2) {
            short sh = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            sum += (long) sh * sh;
        }
        return samples > 0 ? Math.sqrt((double) sum / samples) : 0;
    }
}
