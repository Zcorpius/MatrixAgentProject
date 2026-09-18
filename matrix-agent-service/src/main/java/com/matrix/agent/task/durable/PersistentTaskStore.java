package com.matrix.agent.task.durable;

import com.matrix.agent.host.rpc.CallerContext;
import com.matrix.agent.task.*;


import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.matrix.agent.api.agent.AgentOperationResult;
import com.matrix.agent.api.agent.AgentRequest;
import com.matrix.agent.api.agent.AgentTaskEvent;
import com.matrix.agent.api.agent.AgentTaskSnapshot;
import com.matrix.agent.api.common.AgentTaskState;
import com.matrix.agent.api.common.MatrixErrorCode;
import com.matrix.agent.data.db.AgentTaskDao;
import com.matrix.agent.data.db.AgentTaskEntity;
import com.matrix.agent.data.db.AgentTaskEventEntity;
import com.matrix.agent.data.db.AgentTaskOperationEntity;
import com.matrix.agent.data.db.MatrixDatabase;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Transactional task state store. It is deliberately synchronous: callers use it only for short
 * Binder-boundary transactions, while agent/model work is dispatched separately.
 */
public final class PersistentTaskStore {
    static final class CreateResult {
        final AgentTaskEntity task;
        final boolean newlyCreated;
        final boolean conflict;
        CreateResult(AgentTaskEntity task, boolean newlyCreated, boolean conflict) {
            this.task = task;
            this.newlyCreated = newlyCreated;
            this.conflict = conflict;
        }
    }

    static final class ResumeResult {
        final AgentOperationResult operation;
        final AgentTaskEntity task;
        final boolean shouldStart;
        ResumeResult(AgentOperationResult operation, AgentTaskEntity task, boolean shouldStart) {
            this.operation = operation;
            this.task = task;
            this.shouldStart = shouldStart;
        }
    }

    static final class OperationFailure {
        final AgentOperationResult operation;
        final AgentTaskEvent event;
        OperationFailure(AgentOperationResult operation, AgentTaskEvent event) {
            this.operation = operation;
            this.event = event;
        }
    }

    private final MatrixDatabase database;
    private final AgentTaskDao dao;

    public PersistentTaskStore(@NonNull MatrixDatabase database) {
        this.database = database;
        this.dao = database.agentTaskDao();
    }

    synchronized CreateResult createOrReplay(@NonNull CallerContext caller,
            @NonNull AgentRequest request, @NonNull String requestHash) {
        final CreateResult[] result = new CreateResult[1];
        database.runInTransaction(() -> {
            AgentTaskEntity existing = dao.findByClientRequest(caller.uid, request.clientRequestId);
            if (existing != null) {
                result[0] = new CreateResult(existing, false,
                        !requestHash.equals(existing.requestHash));
                return;
            }
            long now = System.currentTimeMillis();
            AgentTaskEntity entity = new AgentTaskEntity();
            entity.taskId = UUID.randomUUID().toString();
            entity.ownerUid = caller.uid;
            entity.ownerPackage = caller.packageName;
            entity.ownerUserId = caller.userId;
            entity.clientRequestId = request.clientRequestId;
            entity.clientSessionId = request.clientSessionId;
            entity.requestHash = requestHash;
            entity.requestText = request.text;
            entity.state = AgentTaskState.ACCEPTED;
            entity.lastSequence = 1L;
            entity.safeText = "任务已受理";
            entity.errorCode = MatrixErrorCode.SUCCESS;
            entity.createdAtMs = now;
            entity.updatedAtMs = now;
            dao.insert(entity);
            dao.insertEvent(event(entity, AgentTaskEvent.TYPE_STATE_CHANGED, entity.safeText));
            result[0] = new CreateResult(entity, true, false);
        });
        return result[0];
    }

    @Nullable
    synchronized AgentTaskSnapshot snapshot(@NonNull String taskId, int ownerUid) {
        AgentTaskEntity task = dao.getOwned(taskId, ownerUid);
        return task == null ? null : snapshot(task);
    }

    @Nullable
    synchronized AgentTaskEntity ownedTask(@NonNull String taskId, int ownerUid) {
        return dao.getOwned(taskId, ownerUid);
    }

    synchronized AgentTaskEvent transition(@NonNull String taskId, int state, int code,
            @NonNull String safeText) {
        final AgentTaskEvent[] event = new AgentTaskEvent[1];
        database.runInTransaction(() -> {
            AgentTaskEntity task = dao.get(taskId);
            if (task == null) return;
            task.state = state;
            task.errorCode = code;
            task.safeText = bound(safeText, AgentTaskSnapshot.SAFE_SNAPSHOT_MAX_UTF8);
            task.lastSequence++;
            task.updatedAtMs = System.currentTimeMillis();
            if (AgentTaskState.isTerminal(state) || state == AgentTaskState.EXECUTION_UNKNOWN) {
                task.terminalAtMs = task.updatedAtMs;
                task.requestText = "";
            }
            dao.update(task);
            AgentTaskEventEntity stored = event(task, AgentTaskEvent.TYPE_STATE_CHANGED, task.safeText);
            dao.insertEvent(stored);
            event[0] = publicEvent(stored);
        });
        return event[0];
    }

    synchronized List<AgentTaskEvent> eventsAfter(@NonNull String taskId, int ownerUid,
            long afterSequence) {
        if (dao.getOwned(taskId, ownerUid) == null) return java.util.Collections.emptyList();
        List<AgentTaskEventEntity> stored = dao.eventsAfter(taskId, Math.max(0L, afterSequence));
        List<AgentTaskEvent> result = new ArrayList<>(stored.size());
        for (AgentTaskEventEntity event : stored) result.add(publicEvent(event));
        return result;
    }

    /**
     * A Host restart never replays a command automatically: a side effect may already have
     * escaped the old process.  Persist an explicit unknown outcome for client readback instead.
     */
    synchronized int markInterruptedExecutionsUnknown() {
        final int[] count = {0};
        database.runInTransaction(() -> {
            for (AgentTaskEntity task : dao.recoverableAfterProcessDeath()) {
                task.state = AgentTaskState.EXECUTION_UNKNOWN;
                task.errorCode = MatrixErrorCode.SERVICE_RESTARTED;
                task.safeText = "服务重启，执行结果未知；请先读取目标状态后再重试";
                task.lastSequence++;
                task.updatedAtMs = System.currentTimeMillis();
                task.terminalAtMs = task.updatedAtMs;
                dao.update(task);
                dao.insertEvent(event(task, AgentTaskEvent.TYPE_STATE_CHANGED, task.safeText));
                count[0]++;
            }
        });
        return count[0];
    }

    /**
     * Atomically records a control operation. A duplicate key with another request hash is an
     * idempotency conflict; exact duplicates replay their original stable result.
     */
    synchronized AgentOperationResult recordOperation(@NonNull String taskId, int ownerUid,
            @NonNull String operationId, @NonNull String type, @NonNull String requestHash) {
        return recordOperation(taskId, ownerUid, operationId, type, requestHash,
                MatrixErrorCode.SUCCESS);
    }

    synchronized ResumeResult beginResume(@NonNull String taskId, int ownerUid,
            @NonNull String operationId, @NonNull String requestHash) {
        final ResumeResult[] result = new ResumeResult[1];
        database.runInTransaction(() -> {
            AgentTaskEntity task = dao.getOwned(taskId, ownerUid);
            if (task == null) {
                result[0] = new ResumeResult(new AgentOperationResult(
                        MatrixErrorCode.PERMISSION_DENIED, operationId, 0L,
                        AgentTaskState.EXECUTION_UNKNOWN), null, false);
                return;
            }
            AgentTaskOperationEntity existing = dao.findOperation(taskId, operationId);
            if (existing != null) {
                int code = requestHash.equals(existing.requestHash)
                        ? existing.resultCode : MatrixErrorCode.IDEMPOTENCY_CONFLICT;
                result[0] = new ResumeResult(new AgentOperationResult(code, operationId,
                        existing.acceptedSequence, existing.taskState), task, false);
                return;
            }
            int code = task.state == AgentTaskState.DEFERRED && !task.requestText.isEmpty()
                    ? MatrixErrorCode.SUCCESS : MatrixErrorCode.TOO_LATE;
            if (code == MatrixErrorCode.SUCCESS) {
                task.state = AgentTaskState.ACCEPTED;
                task.errorCode = MatrixErrorCode.SUCCESS;
                task.safeText = "恢复请求已受理";
                task.lastSequence++;
                task.updatedAtMs = System.currentTimeMillis();
                dao.update(task);
                dao.insertEvent(event(task, AgentTaskEvent.TYPE_STATE_CHANGED, task.safeText));
            }
            AgentTaskOperationEntity operation = new AgentTaskOperationEntity();
            operation.taskId = taskId;
            operation.clientOperationId = operationId;
            operation.operationType = "resume";
            operation.requestHash = requestHash;
            operation.resultCode = code;
            operation.acceptedSequence = task.lastSequence;
            operation.taskState = task.state;
            operation.createdAtMs = System.currentTimeMillis();
            dao.insertOperation(operation);
            AgentOperationResult publicResult = new AgentOperationResult(code, operationId,
                    operation.acceptedSequence, operation.taskState);
            result[0] = new ResumeResult(publicResult, task, code == MatrixErrorCode.SUCCESS);
        });
        return result[0];
    }

    /**
     * Converts an accepted operation into a stable failure in the same transaction as the task
     * transition. This closes the admission race where the dispatcher rejects work after the
     * operation ledger has already recorded SUCCESS.
     */
    synchronized OperationFailure failAcceptedOperation(@NonNull String taskId,
            @NonNull String operationId, @NonNull String requestHash, int errorCode,
            @NonNull String safeText) {
        final OperationFailure[] result = new OperationFailure[1];
        database.runInTransaction(() -> {
            AgentTaskEntity task = dao.get(taskId);
            AgentTaskOperationEntity operation = dao.findOperation(taskId, operationId);
            if (task == null || operation == null
                    || !requestHash.equals(operation.requestHash)
                    || operation.resultCode != MatrixErrorCode.SUCCESS) {
                throw new IllegalStateException("accepted operation ledger is inconsistent");
            }
            task.state = AgentTaskState.REJECTED;
            task.errorCode = errorCode;
            task.safeText = bound(safeText, AgentTaskSnapshot.SAFE_SNAPSHOT_MAX_UTF8);
            task.lastSequence++;
            task.updatedAtMs = System.currentTimeMillis();
            task.terminalAtMs = task.updatedAtMs;
            task.requestText = "";
            dao.update(task);

            AgentTaskEventEntity storedEvent = event(task,
                    AgentTaskEvent.TYPE_STATE_CHANGED, task.safeText);
            dao.insertEvent(storedEvent);

            operation.resultCode = errorCode;
            operation.acceptedSequence = task.lastSequence;
            operation.taskState = task.state;
            dao.updateOperation(operation);
            result[0] = new OperationFailure(new AgentOperationResult(errorCode, operationId,
                    operation.acceptedSequence, operation.taskState), publicEvent(storedEvent));
        });
        return result[0];
    }

    synchronized AgentOperationResult recordOperation(@NonNull String taskId, int ownerUid,
            @NonNull String operationId, @NonNull String type, @NonNull String requestHash,
            int resultCode) {
        final AgentOperationResult[] result = new AgentOperationResult[1];
        database.runInTransaction(() -> {
            AgentTaskEntity task = dao.getOwned(taskId, ownerUid);
            if (task == null) {
                result[0] = new AgentOperationResult(MatrixErrorCode.PERMISSION_DENIED, operationId,
                        0L, AgentTaskState.EXECUTION_UNKNOWN);
                return;
            }
            AgentTaskOperationEntity existing = dao.findOperation(taskId, operationId);
            if (existing != null) {
                int code = requestHash.equals(existing.requestHash)
                        ? existing.resultCode : MatrixErrorCode.IDEMPOTENCY_CONFLICT;
                result[0] = new AgentOperationResult(code, operationId, existing.acceptedSequence,
                        existing.taskState);
                return;
            }
            AgentTaskOperationEntity operation = new AgentTaskOperationEntity();
            operation.taskId = taskId;
            operation.clientOperationId = operationId;
            operation.operationType = type;
            operation.requestHash = requestHash;
            operation.resultCode = resultCode;
            operation.acceptedSequence = task.lastSequence;
            operation.taskState = task.state;
            operation.createdAtMs = System.currentTimeMillis();
            dao.insertOperation(operation);
            result[0] = new AgentOperationResult(operation.resultCode, operationId,
                    operation.acceptedSequence, operation.taskState);
        });
        return result[0];
    }

    private static AgentTaskEventEntity event(AgentTaskEntity task, int type, String payload) {
        AgentTaskEventEntity event = new AgentTaskEventEntity();
        event.taskId = task.taskId;
        event.sequence = task.lastSequence;
        event.elapsedRealtimeMs = SystemClock.elapsedRealtime();
        event.type = type;
        event.state = task.state;
        event.safePayload = bound(payload, AgentTaskEvent.SAFE_PAYLOAD_MAX_UTF8);
        return event;
    }

    private static AgentTaskSnapshot snapshot(AgentTaskEntity task) {
        return new AgentTaskSnapshot(task.taskId, task.state, task.lastSequence,
                task.safeText, task.errorCode, task.pendingConfirmationId);
    }

    private static AgentTaskEvent publicEvent(AgentTaskEventEntity event) {
        return new AgentTaskEvent(event.taskId, event.sequence, event.elapsedRealtimeMs, event.type,
                event.state, event.safePayload);
    }

    /** UTF-8 byte cap, preserving a valid Java string and avoiding a Binder-size escalation. */
    private static String bound(String value, int maxUtf8Bytes) {
        if (value == null) return "";
        if (value.getBytes(StandardCharsets.UTF_8).length <= maxUtf8Bytes) return value;
        StringBuilder out = new StringBuilder();
        int bytes = 0;
        for (int i = 0; i < value.length();) {
            int codePoint = value.codePointAt(i);
            String unit = new String(Character.toChars(codePoint));
            int next = unit.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + next > maxUtf8Bytes) break;
            out.append(unit);
            bytes += next;
            i += Character.charCount(codePoint);
        }
        return out.toString();
    }
}
