package com.matrix.agent.voice.port;

import com.matrix.agent.voice.*;

/**
 * 音频焦点端口。
 *
 * <p>播报前 {@link #request()}(被拒绝则不播报),播报结束 / 取消 {@link #release()};
 * 焦点丢失经 {@link Listener#onFocusLoss()} 通知上层停止播报。Demo 实现
 * {@code platform.voice.AndroidAudioFocusAdapter}(AudioFocusRequest);本接口让
 * {@code VoiceSessionController} 可在 JVM 单测用 fake 替换。
 */
public interface AudioFocusPort {
    /** 注册回调。 */
    void setListener(Listener listener);

    /**
     * 请求音频焦点。
     *
     * @return {@code true}=已获得焦点;{@code false}=被拒绝(不应播报)
     */
    boolean request();

    /** 释放焦点(播报结束 / 取消 / focus loss)。 */
    void release();

    /** 焦点回调。 */
    interface Listener {
        /** 焦点丢失(LOSS / LOSS_TRANSIENT / LOSS_TRANSIENT_CAN_DUCK)——立即停止播报。 */
        void onFocusLoss();
    }
}
