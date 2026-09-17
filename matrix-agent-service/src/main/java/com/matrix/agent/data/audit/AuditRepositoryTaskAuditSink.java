package com.matrix.agent.data.audit;

import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.task.identity.AgentRequest;
import com.matrix.agent.task.port.TaskAuditSink;

/** SQLCipher 审计仓库到任务层审计端口的基础设施适配器。 */
public final class AuditRepositoryTaskAuditSink implements TaskAuditSink {
    private final AuditRepository repository;

    public AuditRepositoryTaskAuditSink(AuditRepository repository) {
        this.repository = repository == null ? NoopAuditRepository.INSTANCE : repository;
    }

    @Override
    public void persist(AgentOutcome outcome, AgentRequest request) {
        repository.persist(outcome, request);
    }
}
