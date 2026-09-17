package com.matrix.agent.data.memory;

import java.util.List;

/**
 * Semantic Memory 层——抽象知识库(如车型手册、常见问答)。
 *
 * <p>默认实现 {@link EmptySemanticMemorySource} 占位返回空;
 * 引入 embedding + 向量检索后接入。
 */
public interface SemanticMemorySource {
    List<MemorySnippet> recallSemantic(MemoryScope scope, String userText, int maxItems);
}
