package com.matrix.agent.voice;

import com.matrix.agent.voice.port.*;

/**
 * 语音会话 UI 观察者。
 *
 * <p>Controller 把 partial 文本与状态迁移通知给 UI(Demo 第 4 步 Activity 实现它刷新界面)。
 * 方法默认空实现,UI 按需覆盖。
 */
public interface VoiceSessionListener {
    /** ASR partial 中间文本。 */
    default void onPartial(String text) {
    }

    /** ASR final text accepted by validation and about to be submitted to the agent. */
    default void onFinal(String text) {
    }

    /** 会话状态迁移。 */
    default void onStateChanged(VoiceSessionState.State state) {
    }

    /** 语音子系统不可恢复错误(如唤醒不可用),UI 据此提示;手动唤醒或新会话恢复后视为清除。 */
    default void onError(String code) {
    }
}
