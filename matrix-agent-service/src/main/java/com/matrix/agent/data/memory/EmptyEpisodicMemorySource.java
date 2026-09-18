package com.matrix.agent.data.memory;

import java.util.Collections;
import java.util.List;

/**
 * SQLCipher 不可用时的显式 episodic-memory 降级实现。
 */
public final class EmptyEpisodicMemorySource implements EpisodicMemorySource {
    @Override
    public List<MemorySnippet> recallEpisodic(MemoryScope scope, String userText, int maxItems) {
        return Collections.emptyList();
    }
}
