package com.matrix.agent.task.port;

import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.task.identity.AgentRequest;

/** Task 执行完成后的审计发布端口。 */
public interface TaskAuditSink {
    TaskAuditSink NOOP = (outcome, request) -> { };

    /** 审计失败不得改变任务终态；具体持久化策略由基础设施适配器决定。 */
    void persist(AgentOutcome outcome, AgentRequest request);
}
