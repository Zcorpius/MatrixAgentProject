package com.matrix.agent.ondevice.sherpa;

import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;

import java.io.Closeable;

/**
 * Silero VAD（官方 sherpa-onnx {@code Vad} 包装）——语音窗口门控。
 *
 * <p>定位（对齐 Operit OnnxSileroVad 的职责切分）：VAD 不做端点判定，只回答
 * "当前是否处于语音窗口"。窗口语义由模型内建迟滞保证：
 * <ul>
 *   <li>{@code minSpeechSeconds} 连续语音 → 窗口开（{@code isSpeechDetected=true}）；</li>
 *   <li>{@code minSilenceSeconds} 连续静音 → 窗口关（段入队，须 drain）。</li>
 * </ul>
 * 端点判定由 ASR 识别器内建 endpoint rules 负责（见 {@link SherpaAsrEngine}），
 * {@code minSilenceSeconds} 必须大于 rule2 尾静音阈值，保证端点在关窗前触发。</p>
 *
 * <p>线程模型：单消费线程（采音线程）串行调用，无锁。</p>
 */
public final class SherpaSileroVad implements Closeable {

    /** silero 16kHz 标准窗长（512 采样 = 32ms），v4/v5 图一致。 */
    private static final int WINDOW_SAMPLES = 512;
    private static final int SAMPLE_RATE = 16_000;

    /** 窗口开/关沿回调（在 push 调用线程同步触发）。 */
    public interface Listener {
        void onSpeechWindowOpened();

        void onSpeechWindowClosed();
    }

    private final Vad vad;
    private final float[] window = new float[WINDOW_SAMPLES];
    private int windowFilled = 0;
    private boolean windowOpen = false;
    /** 开/关沿回调，可为 null。 */
    private final Listener listener;

    /**
     * @param modelPath        silero_vad.onnx 路径（v4/v5 由模型图自适配）
     * @param threshold        语音概率阈值（0.5 为上游默认；嘈杂环境可升）
     * @param minSilenceSeconds 关窗所需连续静音（必须 &gt; ASR endpoint rule2 尾静音）
     * @param minSpeechSeconds 开窗所需连续语音
     * @param maxSpeechSeconds 单窗最长语音（超长强制分段，与 rule3 对齐）
     * @param listener         开/关沿回调，可为 null
     */
    public SherpaSileroVad(String modelPath, float threshold, float minSilenceSeconds,
            float minSpeechSeconds, float maxSpeechSeconds, Listener listener) {
        SileroVadModelConfig silero = new SileroVadModelConfig();
        silero.setModel(modelPath);
        silero.setThreshold(threshold);
        silero.setMinSilenceDuration(minSilenceSeconds);
        silero.setMinSpeechDuration(minSpeechSeconds);
        silero.setMaxSpeechDuration(maxSpeechSeconds);
        silero.setWindowSize(WINDOW_SAMPLES);

        VadModelConfig config = new VadModelConfig();
        config.setSileroVadModelConfig(silero);
        config.setSampleRate(SAMPLE_RATE);
        config.setNumThreads(1);

        this.vad = new Vad(null, config); // null AssetManager → 文件模式
        this.listener = listener;
    }

    /** 当前是否处于语音窗口（最近一次窗处理后的 {@code isSpeechDetected}）。 */
    public boolean speechWindowOpen() {
        return windowOpen;
    }

    /** 喂 float 采样（与 ASR/KWS 共用同一次 Pcm16 转换结果）。按 512 采样窗推进。 */
    public void push(float[] samples) {
        int offset = 0;
        while (offset < samples.length) {
            int copy = Math.min(samples.length - offset, WINDOW_SAMPLES - windowFilled);
            System.arraycopy(samples, offset, window, windowFilled, copy);
            windowFilled += copy;
            offset += copy;
            if (windowFilled == WINDOW_SAMPLES) {
                windowFilled = 0;
                vad.acceptWaveform(window);
                drainSegments();
                boolean open = vad.isSpeechDetected();
                if (open != windowOpen) {
                    windowOpen = open;
                    if (listener != null) {
                        if (open) listener.onSpeechWindowOpened();
                        else listener.onSpeechWindowClosed();
                    }
                }
            }
        }
    }

    /** 重置窗口状态（新识别轮）。 */
    public void reset() {
        vad.reset();
        windowFilled = 0;
        windowOpen = false;
    }

    @Override
    public void close() {
        vad.release();
    }

    /** 已完结语音段出队丢弃（音频已同步喂给识别器，段数据本身不消费）。 */
    private void drainSegments() {
        while (!vad.empty()) {
            vad.front();
            vad.pop();
        }
    }
}
