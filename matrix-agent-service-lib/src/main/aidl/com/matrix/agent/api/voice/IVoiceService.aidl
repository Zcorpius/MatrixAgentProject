package com.matrix.agent.api.voice;

import com.matrix.agent.api.voice.IVoiceCallback;
import com.matrix.agent.api.voice.IVoiceSessionCallback;
import com.matrix.agent.api.voice.VoiceOperationResult;
import com.matrix.agent.api.voice.VoiceServiceStatus;
import com.matrix.agent.api.voice.VoiceSessionHandle;
import com.matrix.agent.api.voice.VoiceSessionRequest;

/**
 * 受控语音会话与系统语音入口协调。音频采集全部在服务进程内完成；
 * 普通客户端不能获得原始 PCM。热词/VoiceInteraction 入口与客户端 PTT 共用
 * 服务端 VoiceSessionCoordinator 仲裁。
 */
interface IVoiceService {
    VoiceServiceStatus getStatus();

    VoiceOperationResult setEnabled(boolean enabled, String clientOperationId);
    VoiceOperationResult cancelCurrentSession(String clientOperationId);

    /** 客户端"按住说话"入口；会话期间服务端按需升级 microphone 前台服务。 */
    VoiceSessionHandle startUserInitiatedSession(in VoiceSessionRequest request,
                                                 String clientOperationId,
                                                 IVoiceSessionCallback callback);
    VoiceOperationResult stopSession(String sessionId, String clientOperationId);

    void subscribeStatus(IVoiceCallback callback);
    void unsubscribeStatus(IVoiceCallback callback);
}
