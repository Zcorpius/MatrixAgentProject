package com.matrix.agent.voice;

import com.matrix.agent.identity.AgentRequest;

import com.matrix.agent.voice.port.*;

/**
 * ASR 端点后的最终转写结果。
 *
 * <p>不可变值对象,作为 {@link AsrPort.Listener#onFinal(FinalTranscript)} 的载荷,
 * 把语言标签、置信度与置信度可用性贯通到 {@code AgentRequest}。
 *
 * <p><b>不变式</b>:{@code confidenceAvailable=false} 时 {@code confidence} 规范化为 {@code 0f}——
 * 未知置信度绝不伪装成 {@code 1.0}。Vosk 经 {@code VoskResultParser} 解析 word result 的
 * {@code conf} 聚合(非空数组均值→{@code confidenceAvailable=true};无/空数组→false);
 * validator 对 false fail-closed(复述一次仍未知则拒绝)。
 *
 * <p>{@code text} 允许空串(空 final 由 Controller 的转写校验处理,规范化为复述/拒绝),
 * 仅禁止 {@code null};{@code languageTag} 必须非空非空白。
 *
 * <p>不含 speech start/end 时间戳(留给端点/超时治理)。
 */
public final class FinalTranscript {
    private final String text;
    private final String languageTag;
    private final float confidence;
    private final boolean confidenceAvailable;
    public FinalTranscript(String text, String languageTag, float confidence, boolean confidenceAvailable) {
        if (text == null) throw new IllegalArgumentException("text 不能为空");
        if (languageTag == null || languageTag.trim().isEmpty()) {
            throw new IllegalArgumentException("languageTag 不能为空");
        }
        if (!Float.isFinite(confidence) || confidence < 0f || confidence > 1f) {
            throw new IllegalArgumentException("confidence 必须在 0 到 1 之间");
        }
        this.text = text;
        this.languageTag = languageTag;
        this.confidenceAvailable = confidenceAvailable;
        // 未知置信度归零,杜绝下游读到伪造的 1.0。
        this.confidence = confidenceAvailable ? confidence : 0f;
    }

    public String text() { return text; }
    public String languageTag() { return languageTag; }
    public float confidence() { return confidence; }
    public boolean confidenceAvailable() { return confidenceAvailable; }
}
