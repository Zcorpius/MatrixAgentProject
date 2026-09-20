package com.matrix.agent.voice.sherpa;

import android.util.Log;

import com.matrix.agent.voice.VadEvent;
import com.matrix.agent.voice.port.VadPort;

/**
 * 能量 VAD 适配器（Silero ONNX 不可用时的零依赖回退，设计文档 §3.1）。
 *
 * <p>滑动窗口 RMS + 自适应噪声底：连续 {@code speechFrames} 帧超阈值触发 SPEAKING，
 * 连续 {@code silenceFrames} 帧低于阈值触发 ENDPOINT。无 native 依赖，JVM 可测。
 * Silero 模型 VAD 可在后续替换本实现（接口不变）。</p>
 */
public final class EnergyVadAdapter implements VadPort {

    private static final String TAG = "MatrixAgent";

    private static final int FRAME_MS = 30;   // 分析窗口
    private static final int SAMPLE_RATE = 16_000;
    private static final int FRAME_BYTES = SAMPLE_RATE * 2 * FRAME_MS / 1000;

    private Listener listener;
    private final double speechThreshold;
    private final double silenceThreshold;
    private final int speechFramesNeeded;
    private final int silenceFramesNeeded;

    private double noiseFloor = 200.0; // 初始噪声底估计（RMS）
    private int consecutiveSpeech = 0;
    private int consecutiveSilence = 100; // 初始假设静音
    private boolean inSpeech = false;
    private final byte[] frameBuffer = new byte[FRAME_BYTES];
    private int frameFilled = 0;

    /**
     * @param speechRmsThreshold 触发语音的 RMS 阈值（默认 800，约 -32dB）。
     * @param speechFrames       触发语音需连续超阈值帧数（默认 3 ≈ 90ms）。
     * @param silenceFrames      触发端点需连续低阈值帧数（默认 25 ≈ 750ms）。
     */
    public EnergyVadAdapter(double speechRmsThreshold, int speechFrames, int silenceFrames) {
        this.speechThreshold = speechRmsThreshold;
        this.silenceFramesNeeded = silenceFrames;
        this.speechFramesNeeded = speechFrames;
        this.silenceThreshold = speechRmsThreshold * 0.4; // 滞回
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void feed(byte[] pcm, int len) {
        if (pcm == null || len <= 0) return;
        // 累积到完整帧再分析（VAD 帧长固定）
        int offset = 0;
        while (offset < len) {
            int toCopy = Math.min(len - offset, FRAME_BYTES - frameFilled);
            System.arraycopy(pcm, offset, frameBuffer, frameFilled, toCopy);
            frameFilled += toCopy;
            offset += toCopy;
            if (frameFilled >= FRAME_BYTES) {
                analyzeFrame(frameBuffer, FRAME_BYTES);
                frameFilled = 0;
            }
        }
    }

    @Override
    public void reset() {
        consecutiveSpeech = 0;
        consecutiveSilence = 100;
        inSpeech = false;
        frameFilled = 0;
        noiseFloor = 200.0;
    }

    private void analyzeFrame(byte[] frame, int length) {
        double rms = calculateRms(frame, length);
        boolean isLoud = rms > speechThreshold;
        boolean isQuiet = rms < silenceThreshold;

        // 自适应噪声底（慢速跟踪安静段）
        if (isQuiet && !inSpeech) {
            noiseFloor = noiseFloor * 0.95 + rms * 0.05;
        }

        if (isLoud) {
            consecutiveSpeech++;
            consecutiveSilence = 0;
        } else if (isQuiet) {
            consecutiveSilence++;
            consecutiveSpeech = 0;
        } else {
            // 滞回区间：保持当前状态
        }

        Listener l = listener;
        if (l == null) return;

        // 语音开始：连续 N 帧超阈值
        if (!inSpeech && consecutiveSpeech >= speechFramesNeeded) {
            inSpeech = true;
            l.onEvent(VadEvent.SPEECH, 0);
        }

        // 端点：语音中连续 M 帧低于阈值
        if (inSpeech && consecutiveSilence >= silenceFramesNeeded) {
            inSpeech = false;
            consecutiveSpeech = 0;
            l.onEvent(VadEvent.ENDPOINT, 0);
        }
    }

    private static double calculateRms(byte[] pcm, int length) {
        if (length < 2) return 0;
        long sum = 0;
        int samples = length / 2;
        for (int i = 0; i + 1 < length; i += 2) {
            short sample = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            sum += (long) sample * sample;
        }
        return Math.sqrt((double) sum / samples);
    }
}
