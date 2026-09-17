package com.matrix.agent.task.token;

/**
 * Tokenizer 契约——AgentBudget token 维度估算入口。
 *
 * <p>主路径(AgentEngine.estimateConversationChars / enforceMessageBudget)仍用 char,
 * 本接口仅提供能力。切换判断时:
 * <ol>
 *   <li>AppContainer 注入 {@link JtokkitTokenizer}(按 provider 选 encoding);</li>
 *   <li>AgentBudget 暴露 token getter(maxMessageTokens / totalInputTokens);</li>
 *   <li>AgentEngine.estimateConversationChars 改为调 Tokenizer.count;</li>
 *   <li>enforceMessageBudget 改为按 token 截断。</li>
 * </ol>
 *
 * <p>默认装配 {@link CharFallbackTokenizer}(纯 text.length),保证向后兼容。
 */
public interface Tokenizer {
    /** 返回 text 的 token 数估算。text=null 返回 0。 */
    int count(String text);

    /** 返回 tokenizer 使用的编码类型标识(用于 logcat / 调试)。 */
    String encoding();

    /**
     * 把 text 编码后再解码回来——验证 round-trip 一致性。
     *
     * <p>JtokkitTokenizer 走 BPE encode/decode;CharFallbackTokenizer 直接返回原文。
     * 用于诊断 tokenizer 异常(切换主路径前的 sanity check)。
     */
    String encodeAndBack(String text);

    /**
     * 实现是否成功初始化(可走精确 BPE,而非退 char fallback)。
     *
     * <p>默认 true(CharFallbackTokenizer / 未来 fake stub 都算可用);jtokkit 实现可能 false
     * (EncodingException 注册失败 / 词表缺失)。仅诊断信号——主路径失败时仍走 fallback,
     * 不阻塞 Loop;接入 AgentEngine 主路径时 R3 缓解依赖此信号监控 jtokkit 健康度。
     */
    default boolean isAvailable() {
        return true;
    }
}
