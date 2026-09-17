package com.matrix.agent.voice.port;

import com.matrix.agent.voice.*;

/**
 * 端点 / 静音检测端口。
 *
 * <p>消费 PCM,产出 {@link VadEvent}。Demo 下 Vosk 的端点是 ASR Recognizer 的副产品
 * ({@code acceptWaveForm} 返回 true),{@code VoskEndpointAdapter} 复用 ASR 引擎内核
 * 而非独立 VAD;可替换为真正的能量 / 模型 VAD。
 */
public interface VadPort {
    void setListener(Listener listener);

    /** 喂一帧 PCM。端点 / 静音变化时触发 {@link Listener#onEvent(VadEvent)}。len 为有效字节数。 */
    void feed(byte[] pcm, int len);

    /** 重置端点状态(新会话开始时)。 */
    void reset();

    /** VAD 回调。sessionId 用于跨会话校验(防旧会话回调污染新会话)。 */
    interface Listener {
        void onEvent(VadEvent event, long sessionId);
    }
}
