package com.matrix.agent.data.memory;

import java.util.Collections;
import java.util.List;

/**
 * Semantic Memory 占位实现——引入 embedding 后替换。
 */
public final class EmptySemanticMemorySource implements SemanticMemorySource {
    @Override
    public List<MemorySnippet> recallSemantic(MemoryScope scope, String userText, int maxItems) {
        return Collections.emptyList();
    }
}
