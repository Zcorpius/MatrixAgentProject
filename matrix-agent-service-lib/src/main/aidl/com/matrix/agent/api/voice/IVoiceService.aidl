package com.matrix.agent.api.voice;

import com.matrix.agent.api.voice.IVoiceCallback;
import com.matrix.agent.api.voice.IVoiceSessionCallback;
import com.matrix.agent.api.voice.VoiceOperationResult;
import com.matrix.agent.api.voice.VoiceServiceStatus;
import com.matrix.agent.api.voice.VoiceSessionHandle;
import com.matrix.agent.api.voice.VoiceSessionRequest;
import com.matrix.agent.api.download.ModelDownloadInfo;

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

    /** Offline speech-model projection. Progress is persisted by the Host downloader. */
    List<ModelDownloadInfo> listOfflineModels();
    /** Starts installation of every missing bundled offline speech model without opening audio. */
    VoiceOperationResult installOfflineModels(String clientOperationId);
    /** Deletes one idle offline speech model and its resumable download artifact. */
    VoiceOperationResult deleteOfflineModel(String modelId, String clientOperationId);

    void subscribeStatus(IVoiceCallback callback);
    void unsubscribeStatus(IVoiceCallback callback);
}
