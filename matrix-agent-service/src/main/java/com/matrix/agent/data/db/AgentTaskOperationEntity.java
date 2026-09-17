package com.matrix.agent.data.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;

/** Idempotency ledger for mutating task controls. Reuse with changed intent is rejected. */
@Entity(tableName = "agent_task_operation", primaryKeys = {"taskId", "clientOperationId"},
        indices = {@Index(value = {"taskId", "createdAtMs"}, name = "idx_agent_task_operation_time")})
public final class AgentTaskOperationEntity {
    @NonNull public String taskId;
    @NonNull public String clientOperationId;
    @NonNull public String operationType = "";
    @NonNull public String requestHash = "";
    public int resultCode;
    public long acceptedSequence;
    public int taskState;
    public long createdAtMs;
}
