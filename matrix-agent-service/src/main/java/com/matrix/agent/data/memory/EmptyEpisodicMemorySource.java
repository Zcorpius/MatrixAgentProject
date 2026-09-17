package com.matrix.agent.data.memory;

import java.util.Collections;
import java.util.List;

/**
 * Episodic Memory 占位实现——接 SessionHistoryDao 后替换。
 */
public final class EmptyEpisodicMemorySource implements EpisodicMemorySource {
    @Override
    public List<MemorySnippet> recallEpisodic(MemoryScope scope, String userText, int maxItems) {
        return Collections.emptyList();
    }
}
