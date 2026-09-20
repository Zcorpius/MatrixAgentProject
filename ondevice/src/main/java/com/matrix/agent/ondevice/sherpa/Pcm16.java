package com.matrix.agent.ondevice.sherpa;

/**
 * PCM16 LE ↔ float 采样转换。
 *
 * <p>sherpa-onnx 流式 API（OnlineStream.acceptWaveform / Vad / KeywordSpotter）
 * 消费 [-1, 1] float 采样；采集链路（VoiceCaptureController）产出 16kHz/mono/16bit LE
 * byte[]。本类是两套表示的唯一换算点，ASR/VAD/KWS 三处共用，除数 32768 与
 * Operit SherpaSpeechProvider 的 {@code /32768f} 一致。</p>
 */
public final class Pcm16 {

    /** 取前 {@code len} 字节（忽略奇数尾巴的字节）转为 float 采样。 */
    public static float[] toFloats(byte[] pcm, int len) {
        int samples = len / 2;
        float[] out = new float[samples];
        for (int i = 0; i < samples; i++) {
            out[i] = ((short) ((pcm[2 * i] & 0xFF) | (pcm[2 * i + 1] << 8))) / 32768f;
        }
        return out;
    }

    private Pcm16() {
    }
}
