package com.matrix.agent.voice.vosk;

import com.matrix.agent.voice.VadEvent;
import com.matrix.agent.voice.port.VadPort;

/**
 * {@link VadPort} 的 Vosk 实现。
 *
 * <p>委托共享 {@link VoskAsrEngine},把 endpoint 信号({@code acceptWaveForm} 返回 true)转成
 * {@link VadEvent#ENDPOINT}。Demo 下端点是 ASR 的副产品,Controller 只对 {@code AsrPort} feed,
 * 本类的 {@link #feed(byte[])} 不单独处理(endpoint 由 ASR feed 经共享 engine 触发)。
 *
 * <p>后续替换为真正的能量 / 模型 VAD 时,本类整体替换,不影响 {@code VadPort} 契约。
 */
public final class VoskEndpointAdapter implements VadPort {
    private final VoskAsrEngine engine;
    private VadPort.Listener vadListener;
    private VoskAsrEngine.Listener bridge;

    public VoskEndpointAdapter(VoskAsrEngine engine) {
        if (engine == null) throw new IllegalArgumentException("engine 不能为空");
        this.engine = engine;
    }

    @Override
    public void setListener(VadPort.Listener listener) {
        this.vadListener = listener;
        if (bridge != null) engine.removeListener(bridge);
        bridge = new VoskAsrEngine.Listener() {
            @Override
            public void onPartial(String text, long sessionId) {
                // ASR 关注。
            }

            @Override
            public void onFinal(com.matrix.agent.voice.FinalTranscript transcript, long sessionId) {
                // ASR 关注。
            }

            @Override
            public void onEndpoint(long sessionId) {
                if (VoskEndpointAdapter.this.vadListener != null) {
                    VoskEndpointAdapter.this.vadListener.onEvent(VadEvent.ENDPOINT, sessionId);
                }
            }
        };
        engine.addListener(bridge);
    }

    @Override
    public void feed(byte[] pcm, int len) {
        // Demo:端点由 ASR feed 经共享 engine 触发,本类不单独 feed(避免重复喂 PCM)。
    }

    @Override
    public void reset() {
        engine.reset();
    }
}
