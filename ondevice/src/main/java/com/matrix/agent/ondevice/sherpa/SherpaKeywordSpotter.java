package com.matrix.agent.ondevice.sherpa;

import android.util.Log;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

import java.io.Closeable;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Sherpa KWS 关键词唤醒引擎封装——委托官方 {@code KeywordSpotter} Kotlin API。
 *
 * <p>关键词表（keywords.txt）为调用侧资产（音素拼写格式由上游 utils.cc EncodeBase
 * 定义：每行 {@code <token...> @<回显名>}，token 须在模型 tokens.txt 内，回显名不能含空格）。
 * 命中后 reset stream 继续侦听（连续多次唤醒），epoch 语义与 {@link SherpaAsrEngine}
 * 一致：start/stop 各 ++，锁外派发前校验丢弃迟到唤醒。</p>
 */
public final class SherpaKeywordSpotter implements Closeable {

    private static final String TAG = "MatrixAgent";
    private static final int SAMPLE_RATE = 16_000;

    /** 命中回调：keyword 为关键词表 @ 后的回显名。 */
    public interface Listener {
        void onWake(String keyword, long sessionId);
    }

    private final Object nativeLock = new Object();
    private KeywordSpotter spotter;
    private OnlineStream stream;
    private volatile long epoch;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    /**
     * @param files             KWS 模型四元组（chunk-16 int8 套件）
     * @param keywordsFilePath  关键词表路径（音素拼写，UTF-8）
     * @param threshold         触发声学阈值（0~1；越高误唤醒越少、漏唤醒越多）
     * @param boostScore        命中 token 加分
     * @param numTrailingBlanks 命中后所需尾静音 token 数（抑制误触发）
     * @param numThreads        推理线程数
     */
    public SherpaKeywordSpotter(SherpaModelFiles files, String keywordsFilePath,
            float threshold, float boostScore, int numTrailingBlanks, int numThreads) {
        OnlineTransducerModelConfig transducer = new OnlineTransducerModelConfig();
        transducer.setEncoder(files.encoder.getAbsolutePath());
        transducer.setDecoder(files.decoder.getAbsolutePath());
        transducer.setJoiner(files.joiner.getAbsolutePath());

        OnlineModelConfig modelConfig = new OnlineModelConfig();
        modelConfig.setTransducer(transducer);
        modelConfig.setTokens(files.tokens.getAbsolutePath());
        modelConfig.setNumThreads(numThreads);
        modelConfig.setDebug(false);

        KeywordSpotterConfig config = new KeywordSpotterConfig();
        config.setFeatConfig(new FeatureConfig());
        config.setModelConfig(modelConfig);
        config.setKeywordsFile(keywordsFilePath);
        config.setKeywordsThreshold(threshold);
        config.setKeywordsScore(boostScore);
        config.setNumTrailingBlanks(numTrailingBlanks);
        config.setMaxActivePaths(4);

        this.spotter = new KeywordSpotter(null, config); // null AssetManager → 文件模式
        Log.i(TAG, "[SherpaKws] 初始化成功 keywords=" + keywordsFilePath
                + " threshold=" + threshold);
    }

    public void addListener(Listener listener) {
        if (listener != null) listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    /**
     * 启动侦听：新建 stream、递增 epoch。
     *
     * @return 当前 sessionId（epoch）；-1 = stream 创建失败
     */
    public long start() {
        synchronized (nativeLock) {
            if (spotter == null) return -1;
            releaseStreamLocked();
            // 空串 = 沿用 config 的 keywordsFile（Kotlin 参数非空无默认值，传 null 是
            // 即时 NPE——真机闭环 D-验证踩实；每次定制关键词才传非空串）
            stream = spotter.createStream("");
            if (stream == null) {
                Log.e(TAG, "[SherpaKws] stream 创建失败");
                return -1;
            }
            epoch++;
            return epoch;
        }
    }

    /** 喂入一帧 PCM（byte[] 16kHz mono 16bit LE）。 */
    public void feed(byte[] pcm, int len) {
        if (pcm == null || len < 2) return;
        float[] samples = Pcm16.toFloats(pcm, len);

        String keyword = null;
        final long myEpoch;
        synchronized (nativeLock) {
            if (stream == null) return;
            myEpoch = epoch;
            stream.acceptWaveform(samples, SAMPLE_RATE);
            while (spotter.isReady(stream)) spotter.decode(stream);
            String hit = spotter.getResult(stream).getKeyword();
            if (hit != null && !hit.isEmpty()) {
                keyword = hit;
                spotter.reset(stream); // 命中即重置：同一 stream 连续多次唤醒
            }
        }
        if (epoch != myEpoch) return; // stop 已切代次，丢弃迟到命中
        if (keyword != null) {
            for (Listener l : listeners) l.onWake(keyword, myEpoch);
        }
    }

    /** 停止侦听（释放 stream，epoch++）。 */
    public void stop() {
        synchronized (nativeLock) {
            releaseStreamLocked();
            epoch++;
        }
    }

    /** 释放引擎。幂等。 */
    @Override
    public void close() {
        synchronized (nativeLock) {
            releaseStreamLocked();
            if (spotter != null) {
                spotter.release();
                spotter = null;
            }
        }
    }

    private void releaseStreamLocked() {
        if (stream != null) {
            stream.release();
            stream = null;
        }
    }
}
