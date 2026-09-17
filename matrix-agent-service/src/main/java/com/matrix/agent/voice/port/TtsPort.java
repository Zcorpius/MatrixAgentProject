package com.matrix.agent.voice.port;

import com.matrix.agent.voice.*;

/**
 * TTS 播报端口。
 *
 * <p>消费 {@link SpeakableResponse} 播报,完成后回调 {@link Listener#onDone()}。Demo 实现为
 * {@code platform.voice.AndroidTtsAdapter}(第 4 步,Android {@code TextToSpeech});本接口让
 * {@code VoiceSessionController} 可在 JVM 单测中用 fake 替换。
 */
public interface TtsPort {
    /** 注册回调。 */
    void setListener(Listener listener);

    /** 播报 SpeakableResponse(已脱敏、已截断)。utteranceId 由调用方(Controller)生成并传入;speak() 内
     * 同步触发的 onError/onDone 必须用此 id(防 Controller 设期望 id 前回调到达被丢——单线程 executor
     * 掩盖该竞态,换 direct/其他 executor 会暴露)。 */
    void speak(SpeakableResponse response, String utteranceId);

    /** 立即停止当前播报(barge-in / cancel)。 */
    void stop();

    /** TTS 回调。 */
    interface Listener {
        /** 播报完成(utteranceId 用于丢弃旧播报的迟到回调)。 */
        void onDone(String utteranceId);

        /** 播报错误。 */
        void onError(String utteranceId, String code);

        /** TTS 引擎就绪(冷启动异步初始化完成)。default 空,不破坏既有 fake/实现。 */
        default void onReady() {}

        /** TTS 初始化失败(无法播报)。default 空。 */
        default void onInitError(String code) {}
    }
}
