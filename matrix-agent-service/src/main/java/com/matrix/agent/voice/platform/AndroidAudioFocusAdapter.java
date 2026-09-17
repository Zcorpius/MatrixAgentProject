package com.matrix.agent.voice.platform;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;

import com.matrix.agent.voice.port.AudioFocusPort;

/**
 * {@link AudioFocusPort} 的 Android 实现。
 *
 * <p>用 {@link AudioFocusRequest}(API 26+,minSdk 28 满足,无版本分支)请求短时焦点
 * ({@code AUDIOFOCUS_GAIN_TRANSIENT})。{@link #ATTRIBUTES} 为 USAGE_ASSISTANT +
 * CONTENT_TYPE_SPEECH,与 TTS 共用同一组属性({@link #sharedAttributes()}),保证焦点请求与
 * 实际播放流一致(AAOS 焦点/路由据此判定)。
 *
 * <p>focus loss(LOSS / LOSS_TRANSIENT / LOSS_TRANSIENT_CAN_DUCK)一律通知上层 onStop——
 * 默认不自动恢复(GAIN 不回调),让用户重新唤醒。
 */
public final class AndroidAudioFocusAdapter implements AudioFocusPort {
    /** 语音助手播报的共享 AudioAttributes(TTS 与焦点请求共用同一实例)。 */
    public static final AudioAttributes ATTRIBUTES = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();

    private final AudioManager audioManager;
    private final AudioFocusRequest focusRequest;
    private volatile Listener listener;

    public AndroidAudioFocusAdapter(Context context) {
        if (context == null) throw new IllegalArgumentException("context 不能为空");
        this.audioManager = (AudioManager) context.getApplicationContext()
                .getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) throw new IllegalStateException("AudioManager 不可用");
        this.focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(ATTRIBUTES)
                .setOnAudioFocusChangeListener(this::onFocusChange)
                .build();
    }

    /** 供 TTS 取同一组 AudioAttributes(焦点请求与播放流必须一致)。 */
    public static AudioAttributes sharedAttributes() {
        return ATTRIBUTES;
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public boolean request() {
        return audioManager.requestAudioFocus(focusRequest)
                == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    @Override
    public void release() {
        audioManager.abandonAudioFocusRequest(focusRequest);
    }

    private void onFocusChange(int focusChange) {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS
                || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            Listener l = listener;
            if (l != null) l.onFocusLoss();
        }
        // GAIN 不通知:默认不自动恢复,用户重新唤醒。
    }
}
