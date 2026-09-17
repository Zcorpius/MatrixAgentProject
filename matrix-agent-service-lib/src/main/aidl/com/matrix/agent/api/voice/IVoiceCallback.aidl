package com.matrix.agent.api.voice;

import com.matrix.agent.api.voice.VoiceServiceStatus;

/** 语音服务状态回调：一律 oneway；服务端 linkToDeath。 */
oneway interface IVoiceCallback {
    void onVoiceStatusChanged(in VoiceServiceStatus status);
}
