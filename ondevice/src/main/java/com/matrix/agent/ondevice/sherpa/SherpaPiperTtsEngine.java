package com.matrix.agent.ondevice.sherpa;

import android.util.Log;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import java.io.Closeable;
import java.io.File;

/**
 * On-device boundary for the official Sherpa Piper/VITS API.
 *
 * <p>The Host must not compile against {@code com.k2fsa.*}: the AAR is intentionally an
 * implementation detail of {@code :ondevice}. This class exposes only Matrix-owned PCM data
 * and owns native release, matching {@link SherpaAsrEngine}'s role for ASR.</p>
 */
public final class SherpaPiperTtsEngine implements Closeable {
    private static final String TAG = "MatrixAgent";
    private static final int NUM_THREADS = 2;

    /** Immutable generated mono PCM in normalized [-1, 1] float form. */
    public static final class PcmAudio {
        private final float[] samples;
        private final int sampleRate;

        PcmAudio(float[] samples, int sampleRate) {
            this.samples = samples;
            this.sampleRate = sampleRate;
        }

        public float[] samples() { return samples; }
        public int sampleRate() { return sampleRate; }
    }

    private OfflineTts tts;

    public SherpaPiperTtsEngine(File modelDir) {
        if (modelDir == null || !modelDir.isDirectory()) {
            throw new IllegalArgumentException("TTS 模型目录不存在");
        }
        requireFiles(modelDir);
        tts = new OfflineTts(null, createConfig(modelDir));
        Log.i(TAG, "[SherpaTts] 本地 Piper 初始化就绪 sampleRate=" + tts.sampleRate());
    }

    /** Caller serializes invocations; Sherpa native OfflineTts is a single-session object. */
    public PcmAudio generate(String text) {
        if (tts == null) throw new IllegalStateException("TTS 已释放");
        GeneratedAudio generated = tts.generate(text, 0, 1.0f);
        if (generated == null || generated.getSamples() == null || generated.getSamples().length == 0) {
            throw new IllegalStateException("TTS_EMPTY_AUDIO");
        }
        return new PcmAudio(generated.getSamples(), generated.getSampleRate());
    }

    @Override
    public void close() {
        if (tts == null) return;
        tts.release();
        tts = null;
    }

    private static OfflineTtsConfig createConfig(File modelDir) {
        OfflineTtsVitsModelConfig vits = new OfflineTtsVitsModelConfig();
        vits.setModel(path(modelDir, "zh_CN-xiao_ya-medium.onnx"));
        vits.setLexicon(path(modelDir, "lexicon.txt"));
        vits.setTokens(path(modelDir, "tokens.txt"));
        // The Chinese Piper package is lexicon-driven and contains no espeak-ng-data payload.
        vits.setDataDir("");

        OfflineTtsModelConfig model = new OfflineTtsModelConfig();
        model.setVits(vits);
        model.setNumThreads(NUM_THREADS);
        model.setDebug(false);
        model.setProvider("cpu");

        OfflineTtsConfig config = new OfflineTtsConfig();
        config.setModel(model);
        config.setRuleFsts(path(modelDir, "phone.fst") + ","
                + path(modelDir, "date.fst") + "," + path(modelDir, "number.fst"));
        config.setMaxNumSentences(1);
        config.setSilenceScale(0.2f);
        return config;
    }

    private static void requireFiles(File dir) {
        String[] files = {
                "zh_CN-xiao_ya-medium.onnx", "zh_CN-xiao_ya-medium.onnx.json",
                "tokens.txt", "lexicon.txt", "phone.fst", "date.fst", "number.fst",
        };
        for (String name : files) {
            if (!new File(dir, name).isFile()) {
                throw new IllegalArgumentException("TTS 模型文件缺失: " + name);
            }
        }
    }

    private static String path(File modelDir, String name) {
        return new File(modelDir, name).getAbsolutePath();
    }
}
