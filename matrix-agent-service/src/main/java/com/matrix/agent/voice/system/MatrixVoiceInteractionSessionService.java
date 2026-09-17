package com.matrix.agent.voice.system;

import android.content.Context;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;

/**
 * 会话服务(标准 AOSP 入口,follow-up4 ⑥ 修正版本口径):系统经 {@code android.voice_interaction}
 * metadata 的 sessionService 属性绑定此处,{@link #onNewSession} 创建会话——这是
 * {@link VoiceInteractionSessionService} 自 API 21 起的标准机制,非 API 36 专属。
 * 另保留 {@code MatrixVoiceInteractionService.onGetSession} 作为真机(华为 API 31)验证到的
 * 非标准厂商回调实验路径;两路径共用 {@link WakeEntrySession} 与同一个共享 {@link SystemVisEntryAdapter}
 * (去重/冷却闸门状态跨会话保留)。
 */
public final class MatrixVoiceInteractionSessionService extends VoiceInteractionSessionService {
    @Override
    public VoiceInteractionSession onNewSession(Bundle args) {
        return new WakeEntrySession(this);
    }

    /** 无 UI 会话:系统展示即视为一次唤醒触发,随即隐藏(交互由语音闭环完成)。
     * 每实例生成稳定 sessionId(同一会话重复 onShow 命中去重,follow-up2 P2;
     * API 34+ 可改 args 的 KEY_SHOW_SESSION_ID,列批 E)。 */
    static final class WakeEntrySession extends VoiceInteractionSession {
        private final String sessionId = "vis-session-" + java.util.UUID.randomUUID();

        WakeEntrySession(Context context) {
            super(context);
        }

        @Override
        public void onShow(Bundle args, int showFlags) {
            super.onShow(args, showFlags);
            SystemVisEntryAdapter.shared().onSystemTrigger("session-show", sessionId);
            hide(); // 无 UI,立即隐藏
        }
    }
}
