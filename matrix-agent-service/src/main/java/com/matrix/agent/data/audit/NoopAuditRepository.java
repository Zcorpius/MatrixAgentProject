package com.matrix.agent.data.audit;

import java.util.Collections;
import java.util.List;

/**
 * Audit 空实现——JVM 单测 + 装配失败兜底。
 *
 * <p>AgentEngine 旧构造器链默认注入此实现,保证既有 289 测试继续绿(无 Room 依赖)。
 */
public final class NoopAuditRepository implements AuditRepository {
    public static final NoopAuditRepository INSTANCE = new NoopAuditRepository();

    private NoopAuditRepository() {}

    @Override
    public void persist(AuditOutcomeEntry entry) {
        // intentionally empty
    }

    @Override
    public AuditRecord queryByRequest(String userId, String zone, String requestId) {
        return null;
    }

    @Override
    public List<AuditRecord> queryBySession(String userId, String zone, String sessionId, int limit) {
        return Collections.emptyList();
    }
}
