package com.matrix.agent.data.memory;

import com.matrix.agent.session.SessionManager;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Working Memory 默认实现——包装 SessionManager。
 *
 * <p>Only projects a verified, bounded climate state. Redacted turn placeholders are never
 * useful memory and never enter a model prompt.
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
        if (scope == null || sessionId == null || maxItems <= 0 || userText == null) {
            return Collections.emptyList();
        }
        String query = userText.toLowerCase(Locale.ROOT);
        if (!(query.contains("温度") || query.contains("空调") || query.contains("多少度")
                || query.contains("climate") || query.contains("temperature"))) {
            return Collections.emptyList();
        }
        String snapshot = sessionManager.getClimateSnapshotScoped(sessionId,
                scope.getUserId(), scope.getZone());
        if (snapshot == null) return Collections.emptyList();
        return List.of(new MemorySnippet(MemoryLayer.WORKING, scope,
                "last_climate_temperature", snapshot, 1.0, 0L, sessionId));
    }
}
