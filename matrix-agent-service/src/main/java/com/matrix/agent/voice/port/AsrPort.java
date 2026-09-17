package com.matrix.agent.voice.port;

import com.matrix.agent.voice.*;

/**
 * 流式 ASR 端口。
 *
 * <p>消费 16kHz / 单声道 / 16bit little-endian PCM,产出 partial / final 文本回调。
 * Demo 实现为 {@code platform.voice.VoskAsrAdapter}(中文小模型,自由 ASR);量产可替换为
 * OEM 或端侧引擎,不影响 {@link VoiceSessionState} 与 Controller。
 *
 * <p>线程模型:{@link #feed(byte[], int)} 由采音线程串行调用;回调通过 {@link Listener} 触发,
 * Controller 侧应将回调转投到状态机线程后再 {@code transit}。
 */
public interface AsrPort {
    /** 注册回调。 */
    void setListener(Listener listener);

    /** ASR 启动结果:成功 sessionId>=0 + errorCode=null;失败 sessionId=-1 + errorCode 非 null。
     * 失败不触发同步 onError(调用方检查 errorCode),避免依赖 executor 排队时序(direct executor 下同步回调会看到旧状态)。 */
    final class AsrStartResult {
        public final long sessionId;
        public final String errorCode;
        public AsrStartResult(long sessionId, String errorCode) {
            this.sessionId = sessionId;
            this.errorCode = errorCode;
        }
        public boolean success() { return errorCode == null; }
    }

    /** 初始化 ASR 引擎。@return 启动结果(成功含 sessionId,失败含 errorCode,不触发同步 onError)。 */
    AsrStartResult start();

    /**
     * 喂一帧 PCM(16kHz mono 16bit LE)。引擎内部 {@code acceptWaveForm} 返回 true 时
     * 同时触发 {@link Listener#onFinal(FinalTranscript)} 与(若共享)VadPort 的 ENDPOINT。
     * @param len pcm 有效字节数(短帧 read 返回 n 小于 frameBytes 时只送前 len,尾部残留不送入 Vosk)。
     */
    void feed(byte[] pcm, int len);

    /** 释放当前 Recognizer,停止识别。 */
    void stop();

    /** 强制产出当前 final(最大说话时长用,不依赖自然 endpoint)。 */
    void finish();

    /** ASR 回调。sessionId 为产生该回调时的会话标识,Controller 对照当前会话校验。 */
    interface Listener {
        /** 中间识别结果(partial)。 */
        void onPartial(String text, long sessionId);

        /** 端点后的最终结果(final),携带文本、语言、置信度与置信度可用性。 */
        void onFinal(FinalTranscript transcript, long sessionId);

        /** 识别错误(模型未加载 / native 错误)。 */
        void onError(String code, long sessionId);
    }
}
