package com.matrix.agent.voice.sherpa;

import com.matrix.agent.ondevice.sherpa.SherpaAsrEngine;
import com.matrix.agent.voice.FinalTranscript;
import com.matrix.agent.voice.port.AsrPort;

/**
 * {@link AsrPort} 的 Sherpa 实现——VoskAsrAdapter 的可插拔替换。
 *
 * <p>委托 {@link SherpaAsrEngine}，把 engine 的 partial/final 转成
 * {@link AsrPort.Listener} 回调。端点信号不经本端口：由 {@link SherpaEndpointAdapter}
 * 经共享 engine 的 onEndpoint 桥接为 {@code VadEvent.ENDPOINT}（与 Vosk 路径同构，
 * endpoint 属 VAD 域事件，不属于 ASR 错误域）。与 Vosk 版的差异：
 * sherpa 不提供词级置信度——{@code confidenceAvailable=true, confidence=0.8}
 * 保守常量（TranscriptValidator 阈值 0.5 可通过）。</p>
 */
public final class SherpaAsrAdapter implements AsrPort {

    private static final String ERR_START = "SHERPA_ASR_START_FAILED";

    private final SherpaAsrEngine engine;
    private AsrPort.Listener listener;

    public SherpaAsrAdapter(SherpaAsrEngine engine) {
        if (engine == null) throw new IllegalArgumentException("engine 不能为空");
        this.engine = engine;
        engine.addListener(new SherpaAsrEngine.Listener() {
            @Override
            public void onPartial(String text, long sessionId) {
                AsrPort.Listener l = listener;
                if (l != null) l.onPartial(text, sessionId);
            }

            @Override
            public void onFinal(String text, float confidence, boolean confAvailable,
                    long sessionId) {
                AsrPort.Listener l = listener;
                if (l != null) {
                    l.onFinal(new FinalTranscript(text, "zh-CN", confidence, confAvailable),
                            sessionId);
                }
            }

            @Override
            public void onEndpoint(long sessionId) {
                // 端点经 SherpaEndpointAdapter 桥接（VadPort 域），此处不消费
            }
        });
    }

    @Override
    public void setListener(AsrPort.Listener listener) {
        this.listener = listener;
    }

    @Override
    public AsrStartResult start() {
        long sessionId = engine.start();
        return sessionId >= 0
                ? new AsrStartResult(sessionId, null)
                : new AsrStartResult(-1, ERR_START);
    }

    @Override
    public void feed(byte[] pcm, int len) {
        engine.feed(pcm, len);
    }

    @Override
    public void stop() {
        engine.stop();
    }

    @Override
    public void finish() {
        engine.finish();
    }
}
