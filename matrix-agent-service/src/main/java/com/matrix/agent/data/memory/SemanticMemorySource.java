package com.matrix.agent.data.memory;

import java.util.List;

/**
 * Semantic Memory 层——抽象知识库(如车型手册、常见问答)。
 *
 * <p>正常 Host 使用 {@link SemanticMemorySourceImpl} 的关键词 + 显式 score 召回；它无需
 * 向量数据库。{@link EmptySemanticMemorySource} 只用于 SQLCipher 不可用时的显式降级路径。
 */
public interface SemanticMemorySource {
    List<MemorySnippet> recallSemantic(MemoryScope scope, String userText, int maxItems);
}
