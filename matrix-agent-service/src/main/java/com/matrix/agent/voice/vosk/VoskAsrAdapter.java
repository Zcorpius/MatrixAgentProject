package com.matrix.agent.voice.vosk;

import android.util.Log;

import com.matrix.agent.voice.port.AsrPort;
import com.matrix.agent.voice.FinalTranscript;

import java.io.IOException;

/**
 * {@link AsrPort} 的 Vosk 实现。
 *
 * <p>委托 {@link VoskAsrEngine},把 engine 的 partial / final 转成 {@link AsrPort.Listener} 回调。
 * 与 {@link VoskEndpointAdapter} 共享同一 engine 实例(构造注入);endpoint 信号由 EndpointAdapter
 * 监听,本类忽略。
 */
public final class VoskAsrAdapter implements AsrPort {
    private static final String TAG = "MatrixAgent";
    private static final String ERR_START = "VOSK_ASR_START_FAILED";

    private final VoskAsrEngine engine;
    private AsrPort.Listener listener;
    private VoskAsrEngine.Listener bridge;

    public VoskAsrAdapter(VoskAsrEngine engine) {
        if (engine == null) throw new IllegalArgumentException("engine 不能为空");
        this.engine = engine;
    }

    @Override
    public void setListener(AsrPort.Listener listener) {
        this.listener = listener;
        if (bridge != null) engine.removeListener(bridge);
        bridge = new VoskAsrEngine.Listener() {
            @Override
            public void onPartial(String text, long sessionId) {
                if (VoskAsrAdapter.this.listener != null) VoskAsrAdapter.this.listener.onPartial(text, sessionId);
            }

            @Override
            public void onFinal(String text, float confidence, boolean confAvailable, long sessionId) {
                if (VoskAsrAdapter.this.listener != null) {
                    // engine 经 VoskResultParser 解析 word conf,构造真实 FinalTranscript。
                    VoskAsrAdapter.this.listener.onFinal(
                            new FinalTranscript(text, "zh-CN", confidence, confAvailable), sessionId);
                }
            }

            @Override
            public void onEndpoint(long sessionId) {
                // 由 VoskEndpointAdapter 处理。
            }
        };
        engine.addListener(bridge);
    }

    @Override
    public AsrPort.AsrStartResult start() {
        try {
            return new AsrPort.AsrStartResult(engine.start(), null);
        } catch (IOException e) {
            Log.e(TAG, "[Voice] VoskAsr 启动失败: " + e.getMessage());
            return new AsrPort.AsrStartResult(-1L, ERR_START); // 不触发同步 onError,Controller 检查 errorCode
        }
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
        engine.finish(); // 强制产 final(最大说话时长用)
    }
}
