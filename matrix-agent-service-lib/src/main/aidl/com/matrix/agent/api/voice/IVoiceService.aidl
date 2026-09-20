package com.matrix.agent.api.voice;

import com.matrix.agent.api.voice.IVoiceCallback;
import com.matrix.agent.api.voice.IVoiceSessionCallback;
import com.matrix.agent.api.voice.VoiceOperationResult;
import com.matrix.agent.api.voice.VoiceServiceStatus;
import com.matrix.agent.api.voice.VoiceSessionHandle;
import com.matrix.agent.api.voice.VoiceSessionRequest;
import com.matrix.agent.api.voice.TencentTtsConfig;
import com.matrix.agent.api.voice.TencentTtsProvisionInput;
import com.matrix.agent.api.voice.IVoiceTtsConfigCallback;
import com.matrix.agent.api.download.ModelDownloadInfo;
import android.os.ParcelFileDescriptor;

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

    /**
     * 停止采音并冲刷当前识别结果（PTT 松开发送）。
     * 与 stopSession 的区别：stopSession 丢弃 pendingFinal；finishSession 走
     * LISTENING→ENDPOINTING→consumeFinal 路径产出可执行 final（设计文档 §6.2）。
     */
    VoiceOperationResult finishSession(String sessionId, String clientOperationId);

    /** Offline speech-model projection. Progress is persisted by the Host downloader. */
    List<ModelDownloadInfo> listOfflineModels();
    /** Starts installation of every missing bundled offline speech model without opening audio. */
    VoiceOperationResult installOfflineModels(String clientOperationId);
    /** Deletes one idle offline speech model and its resumable download artifact. */
    VoiceOperationResult deleteOfflineModel(String modelId, String clientOperationId);

    /** 当前 ASR 引擎名（"VOSK" / "SHERPA"）。 */
    String getAsrEngine();

    /**
     * 切换 ASR 引擎（"VOSK" / "SHERPA"）。要求会话空闲且模型未加载：Host 回收当前
     * Runtime，下次语音使用按新引擎懒重建。Sherpa 离线模型未安装时仍允许切换——
     * 首次装配会 fail-closed，客户端应先 installOfflineModels 引导下载。
     */
    VoiceOperationResult setAsrEngine(String engine, String clientOperationId);

    /** Safe, non-secret projection. Credentials are never included in this return value. */
    TencentTtsConfig getTencentTtsConfig();
    /** Stores a one-shot SecretId/SecretKey pipe in the Host's Android Keystore domain. */
    VoiceOperationResult provisionTencentTts(in TencentTtsProvisionInput input,
                                             in ParcelFileDescriptor credentialPipe,
                                             String clientOperationId,
                                             IVoiceTtsConfigCallback callback);
    VoiceOperationResult clearTencentTts(String clientOperationId);

    void subscribeStatus(IVoiceCallback callback);
    void unsubscribeStatus(IVoiceCallback callback);
}
