package com.matrix.agent.voice.sherpa;

import com.matrix.agent.ondevice.sherpa.SherpaAsrEngine;
import com.matrix.agent.voice.VadEvent;
import com.matrix.agent.voice.port.VadPort;

/**
 * {@link VadPort} 的 Sherpa 实现——与 VoskEndpointAdapter 同构的桥接：
 * 端点由识别器内建 endpoint rules 判定（engine feed 时触发），
 * 经共享 {@link SherpaAsrEngine} 的 onEndpoint 回调转成 {@link VadEvent#ENDPOINT}。
 * 本类 feed 不单独处理（避免重复喂 PCM）。
 */
public final class SherpaEndpointAdapter implements VadPort {

    private final SherpaAsrEngine engine;
    private VadPort.Listener vadListener;
    private SherpaAsrEngine.Listener bridge;

    public SherpaEndpointAdapter(SherpaAsrEngine engine) {
        if (engine == null) throw new IllegalArgumentException("engine 不能为空");
        this.engine = engine;
    }

    @Override
    public void setListener(VadPort.Listener listener) {
        this.vadListener = listener;
        if (bridge != null) engine.removeListener(bridge);
        bridge = new SherpaAsrEngine.Listener() {
            @Override
            public void onPartial(String text, long sessionId) {
                // ASR 关注
            }

            @Override
            public void onFinal(String text, float confidence, boolean confAvailable,
                    long sessionId) {
                // ASR 关注
            }

            @Override
            public void onEndpoint(long sessionId) {
                if (SherpaEndpointAdapter.this.vadListener != null) {
                    SherpaEndpointAdapter.this.vadListener.onEvent(VadEvent.ENDPOINT, sessionId);
                }
            }
        };
        engine.addListener(bridge);
    }

    @Override
    public void feed(byte[] pcm, int len) {
        // 端点由 ASR feed 经共享 engine 的 endpoint rules 触发，本类不单独 feed
    }

    @Override
    public void reset() {
        engine.reset();
    }
}
