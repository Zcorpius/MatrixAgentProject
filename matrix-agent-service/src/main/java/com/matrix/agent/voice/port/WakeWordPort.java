package com.matrix.agent.voice.port;

import com.matrix.agent.voice.*;

/**
 * 唤醒词端口。
 *
 * <p>消费 16kHz / 单声道 / 16bit LE PCM,检测到唤醒词时触发 {@link Listener#onWake(long)}。
 * Demo 实现为 {@code platform.voice.VoskWakeAdapter}:英文小模型 + grammar 受限词表
 * ("hey matrix")做关键词检测。量产替换为 OEM DSP / VoiceInteractionService 可信唤醒事件。
 *
 * <p><b>唤醒代次(epoch)</b>:start() 递增并经 {@link WakeStartResult} 返回,stop() 作废亦递增;
 * 回调携带产出结果的 recognizer 代次。native 结果在锁外解析/派发期间可能发生 stop+start 重建,
 * 旧 recognizer 的迟到唤醒必须能被丢弃——实现侧派发前校验代次未变,调用方再校验
 * {@code epoch == start() 返回的代次},双重防旧 PCM 迟到唤醒污染新会话。
 */
public interface WakeWordPort {
    /** 唤醒启动结果:成功 errorCode=null 且携带本次 recognizer 代次;失败含 errorCode。
     * 失败不触发同步 onError(调用方检查 errorCode 决定退避重建),避免依赖 executor 排队时序
     * (对称 {@code AsrPort.AsrStartResult});同时让重建成功可被调用方感知并立即清零重试计数。 */
    final class WakeStartResult {
        /** 本次启动的 recognizer 代次(成功时有效);onWake/onError 携带相同代次供校验。 */
        public final long epoch;
        public final String errorCode;
        public WakeStartResult(long epoch, String errorCode) {
            this.epoch = epoch;
            this.errorCode = errorCode;
        }
        public boolean success() { return errorCode == null; }
    }

    void setListener(Listener listener);

    /** 初始化唤醒引擎(加载唤醒模型与 grammar Recognizer),递增代次。
     * @return 启动结果(成功含新代次且无 errorCode,失败含 errorCode,不触发同步 onError)。 */
    WakeStartResult start();

    /** 喂一帧 PCM。命中 grammar 唤醒词时触发 onWake(携带产出结果的代次)。len 为有效字节数(短帧只送前 len)。 */
    void feed(byte[] pcm, int len);

    /** 释放 Recognizer,停止唤醒检测;递增代次作废在途结果。 */
    void stop();

    /** 唤醒回调。 */
    interface Listener {
        /** 检测到唤醒词。epoch 为产出结果的 recognizer 代次,非当前代次时调用方必须丢弃。 */
        void onWake(long epoch);

        /** 引擎错误。epoch 为产出错误的 recognizer 代次,非当前代次时调用方必须丢弃。 */
        void onError(String code, long epoch);
    }
}
