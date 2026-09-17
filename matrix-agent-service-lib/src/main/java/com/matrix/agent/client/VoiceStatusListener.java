package com.matrix.agent.client;

import com.matrix.agent.api.voice.VoiceServiceStatus;

/** 语音服务状态变化的纯 Java listener；在门面事件 Handler 上派发。 */
public interface VoiceStatusListener {

    void onVoiceStatusChanged(VoiceServiceStatus status);
}
