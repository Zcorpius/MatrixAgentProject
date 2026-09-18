package com.matrix.agent.data.memory;

import java.util.List;

/**
 * Episodic Memory 层——历史会话事件(SessionHistory 表)。
 *
 * <p>正常 Host 使用 {@link EpisodicMemorySourceImpl} 经 {@code SessionHistoryDao}
 * 按时间倒序取同 user/zone 的近期历史 session。{@link EmptyEpisodicMemorySource} 只用于
 * SQLCipher 不可用时的显式降级路径。
 */
public interface EpisodicMemorySource {
    List<MemorySnippet> recallEpisodic(MemoryScope scope, String userText, int maxItems);
}
