package com.matrix.agent.task.token;

/**
 * Char-based fallback Tokenizer——纯 text.length(),无 native 依赖。
 *
 * <p>默认装配实现。与旧版 AgentEngine.estimateConversationChars 行为等价,
 * 不改变 289 旧版测试的预算判断结果。
 *
 * <p>切换为 JtokkitTokenizer 时,本类保留作 fallback——provider unknown 或
 * JtokkitTokenizer 初始化失败时退化为 char-based。
 */
public final class CharFallbackTokenizer implements Tokenizer {
    public static final CharFallbackTokenizer INSTANCE = new CharFallbackTokenizer();

    private CharFallbackTokenizer() {}

    @Override
    public int count(String text) {
        return text == null ? 0 : text.length();
    }

    @Override
    public String encoding() {
        return "char-fallback";
    }

    @Override
    public String encodeAndBack(String text) {
        return text == null ? "" : text;
    }
}
