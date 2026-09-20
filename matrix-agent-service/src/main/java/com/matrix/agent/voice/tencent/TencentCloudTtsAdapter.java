package com.matrix.agent.voice.tencent;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Base64;
import android.util.Log;

import com.matrix.agent.api.voice.TencentTtsConfig;
import com.matrix.agent.voice.SpeakableResponse;
import com.matrix.agent.voice.port.ManagedTtsPort;

import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** One-shot Tencent TextToVoice PCM adapter; it owns no credentials after each request completes. */
public final class TencentCloudTtsAdapter implements ManagedTtsPort {
    private static final String TAG = "MatrixAgent";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final SecureTencentTtsConfigStore store;
    private final TencentTtsConfig config;
    private final OkHttpClient http;
    private final Executor lane;
    private final AtomicLong generation = new AtomicLong();
    private final Object trackLock = new Object();
    private volatile Listener listener;
    private volatile boolean closed;
    private AudioTrack activeTrack;

    public TencentCloudTtsAdapter(SecureTencentTtsConfigStore store, TencentTtsConfig config,
            OkHttpClient http, Executor lane) {
        this.store = store;
        this.config = config;
        this.http = http;
        this.lane = lane;
    }

    @Override public void setListener(Listener listener) {
        this.listener = listener;
        if (!closed && listener != null) listener.onReady();
    }

    @Override public void speak(SpeakableResponse response, String utteranceId) {
        if (response == null || utteranceId == null) throw new IllegalArgumentException("response/id required");
        long request = generation.incrementAndGet();
        try {
            lane.execute(() -> synthesizeAndPlay(response.getText(), utteranceId, request));
        } catch (RejectedExecutionException e) {
            reportError(utteranceId, request, "TTS_CLOUD_EXECUTOR_UNAVAILABLE");
        }
    }

    private void synthesizeAndPlay(String text, String id, long request) {
        if (!current(request)) return;
        try {
            byte[] pcm = requestPcm(text, id);
            try {
                if (!current(request)) return;
                play(pcm, request);
                done(id, request);
            } finally {
                java.util.Arrays.fill(pcm, (byte) 0);
            }
        } catch (Exception error) {
            Log.w(TAG, "[TencentTts] synthesize/play failed type="
                    + error.getClass().getSimpleName());
            reportError(id, request, "TTS_CLOUD_REQUEST_FAILED");
        }
    }

    private byte[] requestPcm(String text, String id) throws Exception {
        if (text == null || text.trim().isEmpty()) return new byte[0];
        if (text.codePointCount(0, text.length()) > 150) throw new IOException("text exceeds Tencent limit");
        SecureTencentTtsConfigStore.Credentials credentials = store.loadCredentials();
        if (credentials == null) throw new IOException("credential unavailable");
        JSONObject body = new JSONObject();
        body.put("Text", text).put("SessionId", id).put("Volume", 0).put("Speed", 0)
                .put("ProjectId", 0).put("ModelType", 1).put("VoiceType", config.voiceType)
                .put("PrimaryLanguage", 1).put("SampleRate", 16000).put("Codec", "pcm")
                .put("EmotionCategory", config.emotionCategory)
                .put("EmotionIntensity", config.emotionIntensity);
        String payload = body.toString();
        TencentTc3Signer.SignedHeaders signature = TencentTc3Signer.sign(credentials.secretId,
                credentials.secretKey, payload, System.currentTimeMillis() / 1000L);
        Request request = new Request.Builder().url("https://tts.tencentcloudapi.com/")
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Host", "tts.tencentcloudapi.com")
                .header("X-TC-Action", "TextToVoice").header("X-TC-Version", "2019-08-23")
                .header("X-TC-Timestamp", signature.timestamp)
                .header("Authorization", signature.authorization)
                .post(RequestBody.create(payload, JSON)).build();
        try (Response response = http.newCall(request).execute()) {
            String result = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) throw new IOException("http=" + response.code());
            JSONObject root = new JSONObject(result);
            JSONObject apiError = root.optJSONObject("Response");
            if (apiError == null || apiError.has("Error")) throw new IOException("Tencent API rejected request");
            String audio = apiError.optString("Audio", "");
            String requestId = apiError.optString("RequestId", "-");
            if (audio.isEmpty()) throw new IOException("empty PCM response");
            Log.i(TAG, "[TencentTts] response accepted requestId=" + requestId);
            return Base64.decode(audio, Base64.DEFAULT);
        }
    }

    private void play(byte[] pcm, long request) {
        if (pcm.length == 0) return;
        if ((pcm.length & 1) != 0) throw new IllegalStateException("unaligned PCM");
        int min = AudioTrack.getMinBufferSize(16000, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) throw new IllegalStateException("invalid AudioTrack buffer");
        AudioTrack track = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()).setAudioFormat(new AudioFormat.Builder().setSampleRate(16000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()).setBufferSizeInBytes(Math.max(min, 6400)).setTransferMode(AudioTrack.MODE_STREAM).build();
        if (track.getState() != AudioTrack.STATE_INITIALIZED) { track.release(); throw new IllegalStateException("track init"); }
        synchronized (trackLock) { if (!current(request)) { track.release(); return; } activeTrack = track; }
        try {
            track.play();
            int offset = 0;
            while (offset < pcm.length && current(request)) {
                int wrote = track.write(pcm, offset, pcm.length - offset, AudioTrack.WRITE_BLOCKING);
                if (wrote <= 0) throw new IllegalStateException("track write=" + wrote);
                offset += wrote;
            }
        } finally {
            synchronized (trackLock) { if (activeTrack == track) activeTrack = null; }
            try { track.stop(); } catch (IllegalStateException ignored) { }
            track.release();
        }
    }

    @Override public void stop() {
        generation.incrementAndGet();
        synchronized (trackLock) {
            if (activeTrack == null) return;
            try { activeTrack.pause(); activeTrack.flush(); activeTrack.stop(); } catch (IllegalStateException ignored) { }
        }
    }
    @Override public void shutdown() { closed = true; listener = null; stop(); }
    private boolean current(long value) { return !closed && generation.get() == value; }
    private void done(String id, long request) { if (current(request) && listener != null) listener.onDone(id); }
    private void reportError(String id, long request, String code) {
        if (current(request) && listener != null) listener.onError(id, code);
    }
}
