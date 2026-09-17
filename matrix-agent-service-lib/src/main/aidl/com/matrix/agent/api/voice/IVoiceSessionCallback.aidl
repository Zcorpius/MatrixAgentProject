package com.matrix.agent.api.voice;

/**
 * 受控语音会话回调：只承载安全识别文本、状态与错误码；
 * 不传 PCM、audio format 或原始中间结果。
 */
oneway interface IVoiceSessionCallback {
    void onSessionStateChanged(String sessionId, int state);

    /** 中间识别结果（脱敏后）；state 转换时不携带。 */
    void onPartialText(String sessionId, String text);

    /** 最终识别结果。 */
    void onFinalText(String sessionId, String text);

    void onSessionError(String sessionId, int errorCode);
}
