package com.matrix.agent.voice;

/**
 * ASR 引擎选择（阶段 D-4）。
 *
 * <p>SHERPA 是默认引擎（ROM 预埋归档 + Host 首启本地安装）；VOSK 是轻量备选
 * （模型走网络自动下载）。切换在语音页设置，Host 侧重建 VoiceRuntime
 * （销毁旧装配 → 新引擎装配）。</p>
 */
public enum AsrEngineSelection {
    VOSK,
    SHERPA
}
