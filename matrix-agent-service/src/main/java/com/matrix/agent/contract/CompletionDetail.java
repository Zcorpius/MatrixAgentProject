package com.matrix.agent.contract;

/**
 * 单次模型补全的详情（评估 v1.0 §4.3 debugTraceUi 契约 4）：text + 供应商实际返回的
 * reasoning（若有）。
 *
 * <p>reasoning 是协议字段级透传（OpenAI 兼容 {@code reasoning_content} 等），
 * <b>绝不从输出文本推导</b>；未返回时为 null——调用方写 "reasoning unavailable"。
 * 该类型的消费者是 {@code DebugTraceEmitter}（诊断日志/调试轨迹），不是量产 UI。</p>
 */
public final class CompletionDetail {

    private final String text;
    /** 可空：供应商实际返回的思考字段原文（未经净化——净化在 Emitter 侧统一做）。 */
    private final String reasoningIfPresent;

    public CompletionDetail(String text, String reasoningIfPresent) {
        this.text = text == null ? "" : text;
        this.reasoningIfPresent = reasoningIfPresent;
    }

    public String text() {
        return text;
    }

    /** 可空。 */
    public String reasoningIfPresent() {
        return reasoningIfPresent;
    }
}
