package com.matrix.agent.data.memory;

import com.matrix.agent.session.SessionManager;

import com.matrix.agent.session.SessionContext;

import java.util.List;

/**
 * Working Memory 层——当前会话上下文(SessionContext recentTurns)。
 *
 * <p>默认实现 {@link SessionContextWorkingMemory} 包装 {@link com.matrix.agent.session.SessionManager},
 * 按 sessionId 取最近 N 条 turn 转 MemorySnippet。
 */
public interface WorkingMemorySource {
    List<MemorySnippet> recallWorking(MemoryScope scope, String sessionId, String userText, int maxItems);
}
