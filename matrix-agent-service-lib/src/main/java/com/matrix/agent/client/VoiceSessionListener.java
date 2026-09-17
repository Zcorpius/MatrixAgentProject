package com.matrix.agent.client;

/**
 * 受控语音会话事件的纯 Java listener；只承载安全识别文本、状态与错误码，
 * 不含任何音频数据。在门面事件 Handler 上派发。
 */
public interface VoiceSessionListener {

    void onSessionStateChanged(String sessionId, int state);

    /** 中间识别结果（脱敏后）。 */
    void onPartialText(String sessionId, String text);

    /** 最终识别结果。 */
    void onFinalText(String sessionId, String text);

    void onSessionError(String sessionId, int errorCode);
}
