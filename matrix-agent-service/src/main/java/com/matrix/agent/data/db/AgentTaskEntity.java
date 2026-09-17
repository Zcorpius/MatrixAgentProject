package com.matrix.agent.data.db;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Durable, caller-scoped task record. Request text is stored only inside SQLCipher so a deferred
 * task can be resumed; it is cleared on terminal transition and never crosses the public API.
 */
@Entity(tableName = "agent_task", indices = {
        @Index(value = {"ownerUid", "clientRequestId"}, unique = true,
                name = "idx_agent_task_owner_request"),
        @Index(value = {"ownerUid", "updatedAtMs"}, name = "idx_agent_task_owner_updated")
})
public final class AgentTaskEntity {
    @PrimaryKey @NonNull public String taskId;
    public int ownerUid;
    @NonNull public String ownerPackage = "";
    public int ownerUserId;
    @NonNull public String clientRequestId = "";
    @NonNull public String clientSessionId = "";
    /** SHA-256 of canonical request fields, never the user text itself. */
    @NonNull public String requestHash = "";
    @NonNull public String requestText = "";
    public int state;
    public long lastSequence;
    @NonNull public String safeText = "";
    public int errorCode;
    public String pendingConfirmationId;
    public long createdAtMs;
    public long updatedAtMs;
    public long terminalAtMs;
}
