package com.matrix.agent.ondevice.mnn;

import org.json.JSONObject;

/**
 * MNN 推理上下文统计（对应 native {@code nativeGetContextInfo} 返回的 JSON）。
 *
 * <p>关键字段：{@link #generatedSeqLen}（gen_seq_len）用于判定 LENGTH 截断
 * （{@code generatedSeqLen >= maxNewTokens} → LENGTH）；prefill/decode/sample 耗时用于 UI 指标。
 */
public final class MNNLlmContextInfo {

    public final int promptLen;
    public final int generatedSeqLen;
    public final int totalSeqLen;
    public final long prefillUs;
    public final long decodeUs;
    public final long sampleUs;
    public final int statusCode;
    public final String status;

    public MNNLlmContextInfo(int promptLen, int generatedSeqLen, int totalSeqLen,
            long prefillUs, long decodeUs, long sampleUs, int statusCode, String status) {
        this.promptLen = promptLen;
        this.generatedSeqLen = generatedSeqLen;
        this.totalSeqLen = totalSeqLen;
        this.prefillUs = prefillUs;
        this.decodeUs = decodeUs;
        this.sampleUs = sampleUs;
        this.statusCode = statusCode;
        this.status = status;
    }

    /** 解析 native 返回的 JSON；格式异常返回 null（调用方降级处理）。 */
    public static MNNLlmContextInfo fromJson(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(json);
            return new MNNLlmContextInfo(
                    o.optInt("prompt_len", 0),
                    o.optInt("gen_seq_len", 0),
                    o.optInt("all_seq_len", 0),
                    o.optLong("prefill_us", 0),
                    o.optLong("decode_us", 0),
                    o.optLong("sample_us", 0),
                    o.optInt("status_code", 0),
                    o.optString("status", ""));
        } catch (Exception e) {
            return null;
        }
    }
}
