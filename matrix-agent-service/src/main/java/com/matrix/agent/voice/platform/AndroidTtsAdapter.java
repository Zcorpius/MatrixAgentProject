package com.matrix.agent.voice.platform;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import com.matrix.agent.voice.SpeakableResponse;
import com.matrix.agent.voice.port.ManagedTtsPort;

import java.util.Locale;

/**
 * {@link TtsPort} 的 Android 实现。
 *
 * <p>speak 用 {@code QUEUE_FLUSH} + 每次 唯一 utteranceId(回调带 id 上浮 Controller,
 * 旧播报的迟到 onDone/onError 可与 generation 一起丢弃)。
 *
 * <p>setListener 补发 onReady/onInitError;与焦点请求共用 AudioAttributes。
 *
 * <p><b>线程安全</b>:{@link #lock} 串行化 onInit/speak/stop/shutdown,消除 volatile 的检查-使用竞态
 * (P2-B: shutdown 与迟到 onInit TOCTOU)。progress callback 不持锁(避免与 speak 死锁),靠 volatile
 * closed + listener 快照 + Controller 侧 utteranceMatches/generation 兜底。
 */
public final class AndroidTtsAdapter implements ManagedTtsPort {
    private static final String TAG = "MatrixAgent";

    private TextToSpeech tts;
    private volatile boolean ready = false;
    private volatile boolean initFailed = false;
    private volatile String initErrorCode = null;
    private volatile Listener listener;
    private volatile boolean closed = false;
    /** 串行化 onInit/speak/stop/shutdown,消除检查-使用竞态。 */
    private final Object lock = new Object();

    public AndroidTtsAdapter(Context context) {
        tts = new TextToSpeech(context.getApplicationContext(), status -> {
            synchronized (lock) {
                if (closed) return; // shutdown 已先于迟到 onInit,忽略(不再 setLanguage/置 ready)
                if (status == TextToSpeech.SUCCESS) {
                    int langResult = tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
                    tts.setAudioAttributes(AndroidAudioFocusAdapter.sharedAttributes());
                    if (langResult == TextToSpeech.LANG_MISSING_DATA
                            || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                        initFailed = true;
                        initErrorCode = "TTS_LANG_UNAVAILABLE";
                        Log.e(TAG, "[Voice] TTS 语言不可用: " + langResult);
                        notifyInitResult();
                    } else {
                        ready = true;
                        Log.i(TAG, "[Voice] TTS 初始化就绪");
                        notifyInitResult();
                    }
                } else {
                    initFailed = true;
                    initErrorCode = "TTS_INIT_FAILED";
                    Log.e(TAG, "[Voice] TTS 初始化失败 status=" + status);
                    notifyInitResult();
                }
            }
        });
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) { }
            @Override public void onDone(String utteranceId) {
                if (closed) return;
                Listener l = listener;
                if (l != null) l.onDone(utteranceId);
            }
            @Override public void onError(String utteranceId) {
                if (closed) return;
                Listener l = listener;
                if (l != null) l.onError(utteranceId, "TTS_UTTERANCE_ERROR");
            }
            @Override public void onError(String utteranceId, int errorCode) {
                if (closed) return;
                Listener l = listener;
                if (l != null) l.onError(utteranceId, "TTS_ERROR_" + errorCode);
            }
        });
    }

    /** 调用方须持 {@link #lock}。 */
    private void notifyInitResult() {
        Listener l = listener;
        if (l == null) return;
        if (ready) l.onReady();
        else if (initFailed) l.onInitError(initErrorCode);
    }

    @Override
    public void setListener(Listener listener) {
        synchronized (lock) {
            this.listener = listener;
            notifyInitResult();
        }
    }

    @Override
    public void speak(SpeakableResponse response, String utteranceId) {
        synchronized (lock) {
            if (closed || !ready) {
                Log.w(TAG, "[Voice] TTS 未就绪,跳过播报");
                Listener l = listener;
                if (l != null) l.onError(utteranceId, "TTS_NOT_READY");
                return;
            }
            String text = response.getText();
            if (text == null || text.isEmpty()) {
                Listener l = listener;
                if (l != null) l.onDone(utteranceId);
                return;
            }
            int r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
            if (r != TextToSpeech.SUCCESS) {
                // 同步提交失败(且通常无进度回调):立即上抛 onError,避免只能等播报 watchdog 超时才恢复。
                Log.w(TAG, "[Voice] TTS speak 同步失败 code=" + r);
                Listener l = listener;
                if (l != null) l.onError(utteranceId, "TTS_SPEAK_FAILED");
            }
        }
    }

    @Override
    public void stop() {
        synchronized (lock) {
            if (closed) return;
            try { tts.stop(); } catch (Exception e) { Log.w(TAG, "[Voice] TTS stop: " + e.getMessage()); }
        }
    }

    /** 释放 TTS 引擎(ViewModel onCleared 调)。设 closed + 清 listener,迟到 onInit/speak/stop/progress 全 no-op。 */
    public void shutdown() {
        synchronized (lock) {
            closed = true;
            ready = false;
            listener = null; // 清 listener,迟到的 progress callback 不上抛
            try { tts.stop(); tts.shutdown(); } catch (Exception e) { Log.w(TAG, "[Voice] TTS shutdown: " + e.getMessage()); }
        }
    }
}
