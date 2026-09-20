package com.matrix.agent.voice.sherpa;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

import com.matrix.agent.ondevice.sherpa.SherpaPiperTtsEngine;
import com.matrix.agent.voice.SpeakableResponse;
import com.matrix.agent.voice.port.ManagedTtsPort;

import java.io.File;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * App-owned Chinese Piper TTS backed by the already-packaged Sherpa ONNX runtime.
 *
 * <p>Unlike {@code TextToSpeech}, this adapter neither discovers nor binds an Android TTS
 * service. It loads a verified model from app-private storage, generates normalized float PCM,
 * and plays it through {@link AudioTrack}. Generation and playback are serialized on the voice
 * agent lane: native TTS is single-session, cancellation is generation-based, and no unmanaged
 * thread is introduced outside {@code MatrixExecutorRegistry}.</p>
 */
public final class SherpaPiperTtsAdapter implements ManagedTtsPort {
    private static final String TAG = "MatrixAgent";
    private static final int PCM_BYTES_PER_SAMPLE = 2;

    private final Object lock = new Object();
    private final Executor executionLane;
    private final SherpaPiperTtsEngine tts;
    private final AtomicLong playbackGeneration = new AtomicLong();

    private volatile Listener listener;
    private volatile boolean closed;
    private AudioTrack activeTrack;
    private boolean releaseScheduled;

    public SherpaPiperTtsAdapter(File modelDir, SherpaModelSpec spec, Executor executionLane) {
        if (modelDir == null || !modelDir.isDirectory()) {
            throw new IllegalArgumentException("TTS 模型目录不存在");
        }
        if (spec == null || spec.kind != SherpaModelSpec.Kind.TTS) {
            throw new IllegalArgumentException("需要 TTS 模型规格");
        }
        if (executionLane == null) throw new IllegalArgumentException("TTS 执行 lane 不能为空");
        for (String name : spec.requiredFiles) {
            if (!new File(modelDir, name).isFile()) {
                throw new IllegalArgumentException("TTS 模型文件缺失: " + name);
            }
        }
        this.executionLane = executionLane;
        tts = new SherpaPiperTtsEngine(modelDir);
        Log.i(TAG, "[SherpaTts] 本地 Piper 初始化就绪 model=" + spec.modelId
                + " path=private-storage");
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
        if (!closed && listener != null) listener.onReady();
    }

    @Override
    public void speak(SpeakableResponse response, String utteranceId) {
        if (response == null || utteranceId == null) {
            throw new IllegalArgumentException("response 和 utteranceId 不能为空");
        }
        final long requestGeneration = playbackGeneration.incrementAndGet();
        if (closed) {
            dispatchError(utteranceId, "TTS_CLOSED");
            return;
        }
        try {
            executionLane.execute(() -> generateAndPlay(response.getText(), utteranceId,
                    requestGeneration));
        } catch (RejectedExecutionException rejected) {
            Log.w(TAG, "[SherpaTts] 播报 lane 已关闭");
            dispatchError(utteranceId, "TTS_EXECUTOR_UNAVAILABLE");
        }
    }

    private void generateAndPlay(String text, String utteranceId, long requestGeneration) {
        if (!isCurrent(requestGeneration)) return;
        if (text == null || text.trim().isEmpty()) {
            dispatchDoneIfCurrent(utteranceId, requestGeneration);
            return;
        }
        final SherpaPiperTtsEngine.PcmAudio audio;
        try {
            // OfflineTts is intentionally used only on this serialized lane. stop() marks the
            // generation stale; the native call may finish, but its PCM is never played then.
            audio = tts.generate(text);
        } catch (RuntimeException failed) {
            Log.e(TAG, "[SherpaTts] 生成失败 type=" + failed.getClass().getSimpleName(), failed);
            dispatchErrorIfCurrent(utteranceId, requestGeneration, "TTS_GENERATION_FAILED");
            return;
        }
        if (!isCurrent(requestGeneration)) return;
        try {
            play(audio, requestGeneration);
            dispatchDoneIfCurrent(utteranceId, requestGeneration);
        } catch (RuntimeException failed) {
            Log.e(TAG, "[SherpaTts] 播放失败 type=" + failed.getClass().getSimpleName(), failed);
            dispatchErrorIfCurrent(utteranceId, requestGeneration, "TTS_PLAYBACK_FAILED");
        }
    }

    private void play(SherpaPiperTtsEngine.PcmAudio audio, long requestGeneration) {
        if (audio == null || audio.samples() == null || audio.samples().length == 0) {
            throw new IllegalStateException("TTS_EMPTY_AUDIO");
        }
        int sampleRate = audio.sampleRate();
        int minBufferBytes = AudioTrack.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBufferBytes <= 0) throw new IllegalStateException("AUDIO_TRACK_BUFFER_" + minBufferBytes);
        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build())
                .setBufferSizeInBytes(Math.max(minBufferBytes, sampleRate * PCM_BYTES_PER_SAMPLE / 5))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            track.release();
            throw new IllegalStateException("AUDIO_TRACK_UNINITIALIZED");
        }
        synchronized (lock) {
            if (!isCurrent(requestGeneration)) {
                track.release();
                return;
            }
            activeTrack = track;
        }
        try {
            short[] pcm = toPcm16(audio.samples());
            track.play();
            int offset = 0;
            while (offset < pcm.length && isCurrent(requestGeneration)) {
                int written = track.write(pcm, offset, pcm.length - offset,
                        AudioTrack.WRITE_BLOCKING);
                if (written <= 0) throw new IllegalStateException("AUDIO_TRACK_WRITE_" + written);
                offset += written;
            }
        } finally {
            synchronized (lock) {
                if (activeTrack == track) activeTrack = null;
            }
            try { track.stop(); } catch (IllegalStateException ignored) { }
            track.release();
        }
    }

    static short[] toPcm16(float[] samples) {
        short[] pcm = new short[samples.length];
        for (int i = 0; i < samples.length; i++) {
            float clamped = Math.max(-1f, Math.min(1f, samples[i]));
            pcm[i] = (short) Math.round(clamped * 32767f);
        }
        return pcm;
    }

    @Override
    public void stop() {
        playbackGeneration.incrementAndGet();
        synchronized (lock) {
            if (activeTrack == null) return;
            try { activeTrack.pause(); } catch (IllegalStateException ignored) { }
            try { activeTrack.flush(); } catch (IllegalStateException ignored) { }
            try { activeTrack.stop(); } catch (IllegalStateException ignored) { }
        }
    }

    @Override
    public void shutdown() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            listener = null;
        }
        stop();
        synchronized (lock) {
            if (releaseScheduled) return;
            releaseScheduled = true;
        }
        try {
            // Queue release behind an in-flight generation: calling native release concurrently
            // with generate is undefined. The shared lane is process-owned and stays alive.
            executionLane.execute(() -> {
                try { tts.close(); }
                catch (RuntimeException e) { Log.w(TAG, "[SherpaTts] release: " + e.getMessage()); }
            });
        } catch (RejectedExecutionException ignored) {
            // The host is already terminating its executor; process teardown owns remaining JNI.
        }
    }

    private boolean isCurrent(long requestGeneration) {
        return !closed && playbackGeneration.get() == requestGeneration;
    }

    private void dispatchDoneIfCurrent(String utteranceId, long requestGeneration) {
        if (!isCurrent(requestGeneration)) return;
        Listener target = listener;
        if (target != null) target.onDone(utteranceId);
    }

    private void dispatchErrorIfCurrent(String utteranceId, long requestGeneration, String code) {
        if (!isCurrent(requestGeneration)) return;
        dispatchError(utteranceId, code);
    }

    private void dispatchError(String utteranceId, String code) {
        Listener target = listener;
        if (target != null) target.onError(utteranceId, code);
    }
}
