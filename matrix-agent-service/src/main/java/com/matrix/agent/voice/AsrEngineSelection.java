package com.matrix.agent.voice;

/**
 * ASR 引擎选择（阶段 D-4）。
 *
 * <p>VOSK 是默认引擎（零迁移风险）；SHERPA 是升级候选（需安装模型 + native 库）。
 * 切换在语音页设置，Host 侧重建 VoiceRuntime（销毁旧装配 → 新引擎装配）。</p>
 */
public enum AsrEngineSelection {
    VOSK,
    SHERPA
}
