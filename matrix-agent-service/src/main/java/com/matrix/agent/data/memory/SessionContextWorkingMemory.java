package com.matrix.agent.data.memory;

import com.matrix.agent.session.SessionContext;

import com.matrix.agent.session.SessionManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Working Memory 默认实现——包装 SessionManager。
 *
 * <p>取 sessionId 对应的 SessionContext recentTurns,倒序(最近优先)取前 maxItems 条转 MemorySnippet。
 * sessionId 为 null 或 session 不存在时返回空列表(不抛)。
 *
 * <p>线程安全:依赖 SessionManager.getRecentTurns 的内部同步。
 */
public final class SessionContextWorkingMemory implements WorkingMemorySource {
    private final SessionManager sessionManager;

    public SessionContextWorkingMemory(SessionManager sessionManager) {
        if (sessionManager == null) throw new IllegalArgumentException("sessionManager 不能为空");
        this.sessionManager = sessionManager;
    }

    @Override
    public List<MemorySnippet> recallWorking(MemoryScope scope, String sessionId, String userText, int maxItems) {
        if (sessionId == null || maxItems <= 0) return Collections.emptyList();
        List<String> turns = sessionManager.getRecentTurns(sessionId);
        if (turns.isEmpty()) return Collections.emptyList();
        List<MemorySnippet> result = new ArrayList<>();
        int limit = Math.min(maxItems, turns.size());
        for (int i = turns.size() - 1; i >= 0 && result.size() < limit; i--) {
            String turn = turns.get(i);
            result.add(new MemorySnippet(MemoryLayer.WORKING, scope, "turn-" + i, turn,
                    1.0, 0L, sessionId));
        }
        return Collections.unmodifiableList(result);
    }
}
