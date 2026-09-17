package com.matrix.agent.voice;

import com.matrix.agent.voice.port.*;

/**
 * 语音会话事件。
 *
 * <p>封装一个 {@link EventType} 与可选载荷(ASR 文本/置信度、错误码),由 ASR / VAD /
 * Agent / TTS 等组件投递,驱动 {@link VoiceSessionState} 状态迁移。
 *
 * <p>不可变值对象。无载荷事件用无参静态工厂(如 {@link #wake()}),带载荷事件用对应工厂
 * (如 {@link #finalTranscript(String, float)})。所有工厂对必要参数做 null 校验并抛中文
 * {@link IllegalArgumentException},与项目 {@code core.identity.AgentRequest} 风格一致。
 *
 * @see VoiceSessionState#transit(VoiceEvent)
 */
public final class VoiceEvent {
    private final EventType type;
    private final String text;
    private final float asrConfidence;
    private final String errorCode;

    private VoiceEvent(EventType type, String text, float asrConfidence, String errorCode) {
        this.type = type;
        this.text = text;
        this.asrConfidence = asrConfidence;
        this.errorCode = errorCode;
    }

    /** 事件类型。状态机接受的全部事件。 */
    public enum EventType {
        /** 检测到唤醒(Demo: Vosk 关键词;量产: DSP 唤醒词)。 */
        WAKE,
        /** 采音启动成功,进入 LISTENING。 */
        CAPTURE_STARTED,
        /** ASR partial 中间结果。 */
        PARTIAL,
        /** VAD 检测到有效语音。 */
        SPEECH,
        /** 连续静音超时,触发端点。 */
        SILENCE_TIMEOUT,
        /** ASR final 文本。 */
        FINAL,
        /** ASR 识别出错。 */
        ASR_ERROR,
        /** 识别文本通过校验,可提交 Agent。 */
        ACCEPTED,
        /** 识别文本未通过校验(低置信度/空),请求一次澄清。 */
        REJECTED,
        /** 当前能力需要 pre-execution 确认(VerifyMethod.USER_CONFIRM)。Demo 直接拒绝,不进 CONFIRMING。 */
        NEEDS_CONFIRM,
        /** Agent 返回终态,可生成 SpeakableResponse 播报。 */
        TERMINAL,
        /** 用户/系统取消("停止" / barge-in 取消 / 进程回收)。 */
        CANCEL,
        /** TTS 播报中检测到用户插话。 */
        BARGE_IN,
        /** TTS 播报完成。 */
        TTS_DONE,
        /** 音频焦点丢失(来电/导航/安全告警)。 */
        FOCUS_LOSS,
        /** 通用错误。 */
        ERROR,
        /** 过渡态(CANCELLED / ERROR_ANNOUNCING)清理完成,回 IDLE。 */
        RESET
    }

    /** 唤醒事件。 */
    public static VoiceEvent wake() {
        return new VoiceEvent(EventType.WAKE, null, 0f, null);
    }

    /** 采音启动成功事件。 */
    public static VoiceEvent captureStarted() {
        return new VoiceEvent(EventType.CAPTURE_STARTED, null, 0f, null);
    }

    /** ASR partial 中间结果。text 可为空。 */
    public static VoiceEvent partial(String text, float asrConfidence) {
        return new VoiceEvent(EventType.PARTIAL, text, asrConfidence, null);
    }

    /** VAD 检测到有效语音。 */
    public static VoiceEvent speech() {
        return new VoiceEvent(EventType.SPEECH, null, 0f, null);
    }

    /** 连续静音超时。 */
    public static VoiceEvent silenceTimeout() {
        return new VoiceEvent(EventType.SILENCE_TIMEOUT, null, 0f, null);
    }

    /** ASR final 文本。text 不能为空。 */
    public static VoiceEvent finalTranscript(String text, float asrConfidence) {
        if (text == null) throw new IllegalArgumentException("text 不能为空");
        return new VoiceEvent(EventType.FINAL, text, asrConfidence, null);
    }

    /** ASR 识别出错。errorCode 可为空。 */
    public static VoiceEvent asrError(String errorCode) {
        return new VoiceEvent(EventType.ASR_ERROR, null, 0f, errorCode);
    }

    /** 识别通过。 */
    public static VoiceEvent accepted() {
        return new VoiceEvent(EventType.ACCEPTED, null, 0f, null);
    }

    /** 识别未通过,请求澄清。 */
    public static VoiceEvent rejected() {
        return new VoiceEvent(EventType.REJECTED, null, 0f, null);
    }

    /** 当前能力需要确认。Demo 下触发拒绝。 */
    public static VoiceEvent needsConfirm() {
        return new VoiceEvent(EventType.NEEDS_CONFIRM, null, 0f, null);
    }

    /** Agent 终态。 */
    public static VoiceEvent terminal() {
        return new VoiceEvent(EventType.TERMINAL, null, 0f, null);
    }

    /** 取消。 */
    public static VoiceEvent cancel() {
        return new VoiceEvent(EventType.CANCEL, null, 0f, null);
    }

    /** barge-in 插话。 */
    public static VoiceEvent bargeIn() {
        return new VoiceEvent(EventType.BARGE_IN, null, 0f, null);
    }

    /** TTS 播报完成。 */
    public static VoiceEvent ttsDone() {
        return new VoiceEvent(EventType.TTS_DONE, null, 0f, null);
    }

    /** 音频焦点丢失。 */
    public static VoiceEvent focusLoss() {
        return new VoiceEvent(EventType.FOCUS_LOSS, null, 0f, null);
    }

    /** 通用错误。errorCode 可为空。 */
    public static VoiceEvent error(String errorCode) {
        return new VoiceEvent(EventType.ERROR, null, 0f, errorCode);
    }

    /** 过渡态清理完成,回 IDLE。 */
    public static VoiceEvent reset() {
        return new VoiceEvent(EventType.RESET, null, 0f, null);
    }

    public EventType getType() {
        return type;
    }

    /** ASR 文本(PARTIAL / FINAL),其它事件为 null。 */
    public String getText() {
        return text;
    }

    /** ASR 置信度(PARTIAL / FINAL),其它事件为 0。 */
    public float getAsrConfidence() {
        return asrConfidence;
    }

    /** 错误码(ASR_ERROR / ERROR),其它事件为 null。 */
    public String getErrorCode() {
        return errorCode;
    }
}
