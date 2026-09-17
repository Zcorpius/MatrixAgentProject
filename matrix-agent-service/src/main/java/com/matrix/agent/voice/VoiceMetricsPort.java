package com.matrix.agent.voice;

import com.matrix.agent.voice.port.*;

/**
 * 语音指标端口。
 *
 * <p>记录状态迁移 / 延迟 / error / timeout / cancel,为引擎选型与真机调优提供数据基线。
 * 默认实现 {@link NoopVoiceMetrics}(不记),真机接 RoomMetrics 时替换。
 *
 * <p><b>隐私边界</b>(文档 §5.7):事件只带 state / code / stage / 延迟,**绝不**带 PCM、唤醒前音频、
 * partial/final 原文、Agent prompt、TTS 原文、目的地、联系人、Memory 内容。调用方不得把 transcript
 * 等敏感数据传入本端口。
 *
 * <p><b>范围</b>:只覆盖 Controller 内部可观测指标(状态迁移、Agent 提交→终态延迟、error/timeout/cancel)。
 * 设备级延迟(wake→发声、endpoint 停说、TTS 发声、barge-in 静音)需端口上报 timestamp,留后续。
 */
public interface VoiceMetricsPort {

    /** 状态迁移 + 该段耗时(nanos,基于 System.nanoTime 单调时钟)。 */
    void onStateTransition(VoiceSessionState.State from, VoiceSessionState.State to, long durationNanos);

    /** wake 事件(source 如 "vosk")。 */
    void onWake(String source);

    /** Agent 提交(accepted)→终态(terminal)延迟(nanos)。 */
    void onAgentLatency(long nanos);

    /** 错误(code + stage,stage 如 "TTS"/"ASR"/"AGENT")。 */
    void onError(String code, String stage);

    /** 超时(type + stage,type 如 "SPEECH_START"/"MAX_SPEECH"/"FINAL_WAIT"/"TTS_READY"/"TTS_SPEAK")。 */
    void onTimeout(String type, String stage);

    /** 取消(stage 如 "USER"/"BARGE_IN")。 */
    void onCancel(String stage);
}
