package com.matrix.agent.ondevice;

/**
 * 端侧推理结果（:ondevice 自有，比 String 携带更多状态）。
 *
 * <p>finishReason 判定逻辑（补 Operit 自己都缺的 LENGTH 检测）：
 * <ul>
 *   <li>{@link OnDeviceFinishReason#CANCELLED}：abort 触发；</li>
 *   <li>{@link OnDeviceFinishReason#FAILED}：native 抛错（nativeError 非空）；</li>
 *   <li>{@link OnDeviceFinishReason#LENGTH}：{@code generatedTokens >= maxNewTokens}；</li>
 *   <li>{@link OnDeviceFinishReason#STOP}：其余（正常 EOS）。</li>
 * </ul>
 * token/timing 字段来自 {@code nativeGetContextInfo}。
 */
public final class GenerationResult {

    public final String text;
    public final OnDeviceFinishReason finishReason;
    public final int promptTokens;
    public final int generatedTokens;
    public final long prefillUs;
    public final long decodeUs;
    public final long sampleUs;
    /** native 失败原因；非 FAILED 时为 null。 */
    public final String nativeError;

    public GenerationResult(String text, OnDeviceFinishReason finishReason,
            int promptTokens, int generatedTokens,
            long prefillUs, long decodeUs, long sampleUs, String nativeError) {
        this.text = text;
        this.finishReason = finishReason;
        this.promptTokens = promptTokens;
        this.generatedTokens = generatedTokens;
        this.prefillUs = prefillUs;
        this.decodeUs = decodeUs;
        this.sampleUs = sampleUs;
        this.nativeError = nativeError;
    }

    /** 便利工厂：native 失败。 */
    public static GenerationResult failed(String nativeError) {
        return new GenerationResult("", OnDeviceFinishReason.FAILED, 0, 0, 0, 0, 0, nativeError);
    }
}
