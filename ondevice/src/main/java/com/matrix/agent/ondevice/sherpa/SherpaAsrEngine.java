package com.matrix.agent.ondevice.sherpa;

import android.util.Log;

import com.k2fsa.sherpa.onnx.EndpointConfig;
import com.k2fsa.sherpa.onnx.EndpointRule;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

import java.io.Closeable;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Sherpa-NN 流式 ASR 引擎封装——委托官方 {@code com.k2fsa.sherpa.onnx} Kotlin API
 * （Operit 模式：消费上游官方 JNI，不手写 C++）。
 *
 * <p>分层：本类只管"喂 PCM → partial/final/endpoint 回调"与生命周期；
 * 端点判定用识别器内建 endpoint rules（rule1/2/3，Operit 实测参数），
 * 可选 {@link SherpaSileroVad} 作前置语音窗口门控——静音期不喂识别器
 * （省 CPU + 抑制静音幻觉文本），窗口开启瞬间回放 0.5s preroll 补首字
 * （对齐系统 KWS→ASR preroll 哲学，窗口判定由 VAD 内建迟滞保证）。</p>
 *
 * <p>线程安全：feed 在采音线程串行调用；start/stop/reset/close 在状态机线程调用，
 * 细粒度锁保护 native 对象（feed+decode 与 stop/reset 互斥），回调在锁外。
 * epoch（代次）在 start/stop 各 ++，锁外派发前校验，丢弃 stop 后的迟到回调。</p>
 */
public final class SherpaAsrEngine implements Closeable {

    private static final String TAG = "MatrixAgent";
    private static final int SAMPLE_RATE = 16_000;

    /**
     * 端点规则（Operit 实测参数，秒）：
     * rule1 = 2.4s 纯静音收口（含从头静音，兜底空转）；
     * rule2 = 1.2s 说话后尾静音（主判定）；
     * rule3 = 20s 最长语音强制收口。
     * 约束：rule2 &lt; SherpaSileroVad 的 minSilenceSeconds（1.6s），
     * 保证端点在 VAD 关窗前于识别器内部触发（门控开启期间尾静音可达识别器）。
     */
    private static final float ENDPOINT_RULE1_TRAILING_SILENCE = 2.4f;
    private static final float ENDPOINT_RULE2_TRAILING_SILENCE = 1.2f;
    private static final float ENDPOINT_RULE3_MAX_UTTERANCE = 20.0f;

    /** 门控关闭期间保留的 preroll 深度（采样数）。 */
    private static final int PREROLL_SAMPLES = SAMPLE_RATE / 2;

    /** 引擎回调：partial / final / endpoint。 */
    public interface Listener {
        void onPartial(String text, long sessionId);

        void onFinal(String text, float confidence, boolean confAvailable, long sessionId);

        void onEndpoint(long sessionId);
    }

    private final Object nativeLock = new Object();
    private OnlineRecognizer recognizer;
    private OnlineStream stream;
    private volatile long epoch; // start/stop 各 ++，feed 锁外校验丢弃 stop 后回调
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    /** 语音窗口门控，可为 null（无门控：全量喂识别器）。 */
    private final SherpaSileroVad vadGate;
    private boolean gateOpen = false;
    private final float[] preroll = new float[PREROLL_SAMPLES];
    private int prerollSize = 0;

    /**
     * @param files     模型四元组（构造即校验存在）
     * @param vadGate   Silero 语音窗口门控，null 表示不门控
     * @param numThreads 推理线程数（建议 2：大核，与 MNN LLM 分时共存）
     * @throws IllegalStateException native 库缺失（ExceptionInInitializerError/
     *         UnsatisfiedLinkError 由装配工厂翻译）或模型加载失败
     */
    public SherpaAsrEngine(SherpaModelFiles files, SherpaSileroVad vadGate, int numThreads) {
        this.vadGate = vadGate;
        this.recognizer = new OnlineRecognizer(null, buildConfig(files, numThreads));
        Log.i(TAG, "[SherpaAsr] 初始化成功 encoder=" + files.encoder.getName()
                + " vadGate=" + (vadGate != null));
    }

    static OnlineRecognizerConfig buildConfig(SherpaModelFiles files, int numThreads) {
        OnlineTransducerModelConfig transducer = new OnlineTransducerModelConfig();
        transducer.setEncoder(files.encoder.getAbsolutePath());
        transducer.setDecoder(files.decoder.getAbsolutePath());
        transducer.setJoiner(files.joiner.getAbsolutePath());

        OnlineModelConfig modelConfig = new OnlineModelConfig();
        modelConfig.setTransducer(transducer);
        modelConfig.setTokens(files.tokens.getAbsolutePath());
        modelConfig.setNumThreads(numThreads);
        modelConfig.setDebug(false);

        EndpointConfig endpoint = new EndpointConfig();
        endpoint.setRule1(new EndpointRule(false, ENDPOINT_RULE1_TRAILING_SILENCE, 0f));
        endpoint.setRule2(new EndpointRule(true, ENDPOINT_RULE2_TRAILING_SILENCE, 0f));
        endpoint.setRule3(new EndpointRule(false, 0f, ENDPOINT_RULE3_MAX_UTTERANCE));

        OnlineRecognizerConfig config = new OnlineRecognizerConfig();
        config.setFeatConfig(new FeatureConfig()); // 默认 16k / 80 维 fbank
        config.setModelConfig(modelConfig);
        config.setEndpointConfig(endpoint);
        config.setEnableEndpoint(true);
        // 流式 transducer 仅支持 greedy_search / modified_beam_search（modified_greedy_search
        // 是 offline 专属——上游 C++ 对未知方法直接 exit(-1)，真机闭环 D-验证踩实）。
        // beam 版在音素组合纠错上优于纯贪心，Mi 9 SE 实测 182MB int8 加载 5s、可承受。
        config.setDecodingMethod("modified_beam_search");
        config.setMaxActivePaths(4);
        return config;
    }

    public void addListener(Listener listener) {
        if (listener != null) listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /**
     * 启动新识别轮：新建 stream、重置门控、递增 epoch。
     *
     * @return 当前 sessionId（epoch），回调校验用；-1 = stream 创建失败
     */
    public long start() {
        synchronized (nativeLock) {
            if (recognizer == null) return -1;
            releaseStreamLocked();
            // 空串 = 默认流（Kotlin 参数非空无默认值，Java 传 null 即时 NPE）
            stream = recognizer.createStream("");
            if (stream == null) {
                Log.e(TAG, "[SherpaAsr] stream 创建失败");
                return -1;
            }
            resetGateLocked();
            epoch++;
            return epoch;
        }
    }

    /** 喂入一帧 PCM（byte[] 16kHz mono 16bit LE，len 为有效字节数）。 */
    public void feed(byte[] pcm, int len) {
        if (pcm == null || len < 2) return;
        float[] samples = Pcm16.toFloats(pcm, len); // 锁外转换

        String partialText = null;
        String finalText = null;
        boolean endpoint = false;
        final long myEpoch;
        synchronized (nativeLock) {
            if (stream == null) return;
            myEpoch = epoch;

            boolean feedRecognizer = true;
            if (vadGate != null) {
                vadGate.push(samples);
                boolean open = vadGate.speechWindowOpen();
                if (open && !gateOpen) {
                    // 窗口开沿：先回放 preroll 再喂本帧，VAD 开窗迟滞丢失的首字由此补回
                    gateOpen = true;
                    if (prerollSize > 0) {
                        stream.acceptWaveform(java.util.Arrays.copyOf(preroll, prerollSize),
                                SAMPLE_RATE);
                        prerollSize = 0;
                    }
                } else if (!open) {
                    gateOpen = false;
                    appendPrerollLocked(samples);
                    feedRecognizer = false; // 静音窗口：不喂识别器
                }
            }

            if (feedRecognizer) {
                stream.acceptWaveform(samples, SAMPLE_RATE);
                while (recognizer.isReady(stream)) recognizer.decode(stream);
                String text = recognizer.getResult(stream).getText();
                if (recognizer.isEndpoint(stream)) {
                    endpoint = true;
                    finalText = text;
                    recognizer.reset(stream);
                } else {
                    partialText = text;
                }
            }
        }
        // 锁外回调（epoch 校验：stop 已切代次则丢弃）
        if (epoch != myEpoch) return;
        if (endpoint) {
            if (finalText != null && !finalText.isEmpty()) {
                for (Listener l : listeners) {
                    l.onFinal(finalText, 0.8f, true, myEpoch); // sherpa 不提供词级置信度
                }
            }
            for (Listener l : listeners) l.onEndpoint(myEpoch);
        } else if (partialText != null && !partialText.isEmpty()) {
            for (Listener l : listeners) l.onPartial(partialText, myEpoch);
        }
    }

    /** 强制产出 final（最大说话时长用）：inputFinished + 解码排空。 */
    public void finish() {
        String finalText = null;
        final long myEpoch;
        synchronized (nativeLock) {
            if (stream == null) return;
            myEpoch = epoch;
            stream.inputFinished();
            while (recognizer.isReady(stream)) recognizer.decode(stream);
            finalText = recognizer.getResult(stream).getText();
            recognizer.reset(stream);
            resetGateLocked();
        }
        if (epoch != myEpoch) return;
        if (finalText != null && !finalText.isEmpty()) {
            for (Listener l : listeners) {
                l.onFinal(finalText, 0.8f, true, myEpoch);
            }
        }
    }

    /** 重置流状态（丢弃当前 partial）并重置门控。 */
    public void reset() {
        synchronized (nativeLock) {
            if (stream != null) recognizer.reset(stream);
            resetGateLocked();
        }
    }

    /** 停止识别（释放 stream，epoch++）。 */
    public void stop() {
        synchronized (nativeLock) {
            releaseStreamLocked();
            resetGateLocked();
            epoch++;
        }
    }

    /** 释放引擎。幂等。 */
    @Override
    public void close() {
        synchronized (nativeLock) {
            releaseStreamLocked();
            if (recognizer != null) {
                recognizer.release();
                recognizer = null;
            }
        }
    }

    private void releaseStreamLocked() {
        if (stream != null) {
            stream.release();
            stream = null;
        }
    }

    private void resetGateLocked() {
        if (vadGate != null) vadGate.reset();
        gateOpen = false;
        prerollSize = 0;
    }

    private void appendPrerollLocked(float[] samples) {
        if (samples.length >= PREROLL_SAMPLES) {
            System.arraycopy(samples, samples.length - PREROLL_SAMPLES, preroll, 0,
                    PREROLL_SAMPLES);
            prerollSize = PREROLL_SAMPLES;
            return;
        }
        int keep = PREROLL_SAMPLES - samples.length;
        if (keep > 0) System.arraycopy(preroll, prerollSize - Math.min(keep, prerollSize),
                preroll, 0, Math.min(keep, prerollSize));
        int existing = Math.min(keep, prerollSize);
        System.arraycopy(samples, 0, preroll, existing, samples.length);
        prerollSize = Math.min(PREROLL_SAMPLES, existing + samples.length);
    }
}
