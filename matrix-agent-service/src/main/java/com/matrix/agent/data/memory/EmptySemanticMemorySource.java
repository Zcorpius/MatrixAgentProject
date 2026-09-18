package com.matrix.agent.data.memory;

import java.util.Collections;
import java.util.List;

/**
 * SQLCipher 不可用时的显式 semantic-memory 降级实现。
 */
public final class EmptySemanticMemorySource implements SemanticMemorySource {
    @Override
    public List<MemorySnippet> recallSemantic(MemoryScope scope, String userText, int maxItems) {
        return Collections.emptyList();
    }
}
