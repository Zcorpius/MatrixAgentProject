package com.matrix.agent.voice.system;

import android.content.Intent;
import android.speech.RecognitionService;
import android.os.Bundle;

/**
 * 占位 RecognitionService(批 C follow-up2 P0):AOSP 解析 voice-interaction-service metadata
 * 时要求 recognitionService 必须存在,缺失即 parse error(系统拒绝/重置默认助手)。
 * Vosk 不实现 SpeechRecognizer 协议,本服务对系统侧识别请求统一返回 error
 * (Callback.onError)——真实语音识别走应用内 VoiceRuntime,不经系统 RecognitionService。
 */
public final class MatrixRecognitionService extends RecognitionService {
    @Override
    protected void onStartListening(Intent recognizerIntent, Callback callback) {
        try {
            callback.error(android.speech.SpeechRecognizer.ERROR_CLIENT); // 系统协议不可用,统一错误返回(不悬挂调用方)
        } catch (android.os.RemoteException ignored) { }
    }

    @Override protected void onStopListening(Callback callback) { }
    @Override protected void onCancel(Callback callback) { }
    @Override public void onDestroy() { super.onDestroy(); }
}
