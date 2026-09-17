package com.matrix.agent.task.token;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;

/**
 * 基于 jtokkit 的 BPE Tokenizer 实现。
 *
 * <p>默认不装配到 AgentEngine 主路径——本类仅提供能力。
 * 切换时按 provider 选 encoding:
 * <ul>
 *   <li>OpenAI gpt-4o / gpt-4-turbo → {@link EncodingType#O200K_BASE};</li>
 *   <li>OpenAI gpt-3.5 / gpt-4 (non-turbo) / Anthropic 近似 → {@link EncodingType#CL100K_BASE};</li>
 *   <li>Gemini 等私有 tokenizer → 退化为 {@link CharFallbackTokenizer}。</li>
 * </ul>
 *
 * <p>构造时初始化 {@link EncodingRegistry}(约 5MB 常驻内存);单例化以避免重复 init。
 *
 * <p>jtokkit 1.1.0 的 encodeOrdinary / decode 使用自带 {@link IntArrayList},
 * 不是 JDK {@code List<Integer>} 也不是 fastutil 原生类型——避免装箱开销。
 */
public final class JtokkitTokenizer implements Tokenizer {
    private final Encoding encoding;

    public JtokkitTokenizer() {
        this(EncodingType.O200K_BASE);
    }

    public JtokkitTokenizer(EncodingType type) {
        if (type == null) throw new IllegalArgumentException("EncodingType 不能为空");
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        this.encoding = registry.getEncoding(type);
    }

    @Override
    public int count(String text) {
        if (text == null || text.isEmpty()) return 0;
        return encoding.countTokensOrdinary(text);
    }

    @Override
    public String encoding() {
        return encoding.getName();
    }

    @Override
    public String encodeAndBack(String text) {
        if (text == null) return "";
        IntArrayList tokens = encoding.encodeOrdinary(text);
        return encoding.decode(tokens);
    }
}
