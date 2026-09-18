package com.matrix.agent.task.persistence;

import com.matrix.agent.data.audit.AuditRepository;
import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.task.port.TaskAuditSink;

/** 将任务领域结果投影为审计持久化命令的 task 侧适配器。 */
public final class AuditRepositoryAuditSink implements TaskAuditSink {
    private final AuditRepository repository;

    public AuditRepositoryAuditSink(AuditRepository repository) {
        this.repository = repository == null
                ? com.matrix.agent.data.audit.NoopAuditRepository.INSTANCE : repository;
    }

    @Override
    public void persist(AgentOutcome outcome, AgentRequest request) {
        repository.persist(AuditOutcomeEntryFactory.from(outcome, request));
    }
}
