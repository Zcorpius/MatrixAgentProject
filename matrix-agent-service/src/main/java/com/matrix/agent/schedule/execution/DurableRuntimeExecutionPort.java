package com.matrix.agent.schedule.execution;

import static com.matrix.agent.api.common.MatrixErrorCode.*;
import static com.matrix.agent.api.schedule.ScheduleCodes.*;
import com.matrix.agent.api.schedule.ScheduleSpec;
import com.matrix.agent.data.schedule.ScheduleAcceptanceEntity;
import com.matrix.agent.schedule.domain.ScheduleCodec;
import com.matrix.agent.schedule.domain.ScheduleFailure;
import com.matrix.agent.schedule.domain.ScheduleNormalizer;
import com.matrix.agent.schedule.store.ScheduleStore;

/** Same-hash replay, different-hash conflict, and acceptance event are one encrypted transaction. */
public final class DurableRuntimeExecutionPort implements RuntimeExecutionPort {
    private final ScheduleStore store;
    private final java.util.function.LongSupplier wallClock;
    public DurableRuntimeExecutionPort(ScheduleStore store) { this(store, System::currentTimeMillis); }
    public DurableRuntimeExecutionPort(ScheduleStore store, java.util.function.LongSupplier wallClock) {
        this.store = store;
        this.wallClock = wallClock;
    }
    @Override public Acceptance accept(String requestId, String runId, ScheduleSpec spec,
            AuthorizationSnapshot authorization, ExecutionContext context) {
        ScheduleNormalizer.requireUuid(requestId);
        ScheduleNormalizer.requireUuid(runId);
        String encoded = ScheduleCodec.spec(spec);
        String hash = ScheduleCodec.digest(requestId, runId, encoded, authorization.encoded(),
                authorization.actor().name(), authorization.zone().wireValue(), context.stepId(),
                Long.toString(context.dataEpoch()), Long.toString(context.expiresAt()));
        return store.database().runInTransaction(() -> {
            if (store.currentEpoch() != context.dataEpoch()) throw new ScheduleFailure(PERMISSION_DENIED, "STALE_EPOCH");
            var previous = store.dao().acceptance(requestId);
            if (previous != null) {
                if (!previous.requestHash.equals(hash)) throw new ScheduleFailure(IDEMPOTENCY_CONFLICT, "执行请求编号对应不同载荷");
                return project(previous);
            }
            var run = store.dao().run(runId);
            if (run == null || !run.dispatchClaimed || run.dataEpoch != context.dataEpoch()
                    || run.state == CANCEL_REQUESTED || terminalRun(run.state)) throw new ScheduleFailure(INVALID_STATE, "运行不可受理");
            if (!run.authorizationJson.equals(authorization.encoded()) || !run.actor.equals(authorization.actor().name())
                    || !run.zone.equals(authorization.zone().wireValue())) throw new ScheduleFailure(PERMISSION_DENIED, "运行授权不一致");
            if (context.expiresAt() != run.expiresAt || wallClock.getAsLong() > run.expiresAt) {
                throw new ScheduleFailure(INVALID_STATE, "运行期限不一致或已过期");
            }
            if (context.stepId().isEmpty()) {
                if (!run.runtimeRequestId.equals(requestId) || !run.specJson.equals(encoded)) throw new ScheduleFailure(IDEMPOTENCY_CONFLICT, "冻结运行规格不一致");
            } else {
                var step = store.dao().step(runId, context.stepId());
                if (step == null || !step.runtimeRequestId.equals(requestId)) throw new ScheduleFailure(INVALID_STATE, "步骤身份不一致");
            }
            ScheduleAcceptanceEntity row = new ScheduleAcceptanceEntity();
            row.runtimeRequestId = requestId; row.executionHandle = requestId; row.runId = runId; row.stepId = context.stepId();
            row.requestHash = hash; row.ownerUid = run.ownerUid; row.dataEpoch = run.dataEpoch;
            row.state = QUEUED; row.specJson = encoded; row.actor = run.actor; row.zone = run.zone;
            row.acceptedAt = wallClock.getAsLong();
            row.acceptedSequence = store.event(store.dao().definition(run.scheduleId), runId, "EXECUTION_ACCEPTED", "", row.acceptedAt);
            store.dao().insertAcceptance(row);
            if (context.stepId().isEmpty()) {
                store.dao().deleteRunOutbox(runId);
                // Keep a recovery wake until the terminal receipt is durable. Acceptance alone
                // must not remove the only wake source before a worker actually starts.
                var continuation = new com.matrix.agent.data.schedule.ScheduleOutboxEntity();
                continuation.effectId = "execute:" + runId; continuation.kind = "EXECUTE";
                continuation.runId = runId; continuation.scheduleId = run.scheduleId;
                continuation.dataEpoch = run.dataEpoch; continuation.generation = run.dispatchGeneration;
                continuation.nextAttemptAt = row.acceptedAt + 30_000;
                continuation.cutoffAt = run.expiresAt;
                store.dao().putOutbox(continuation);
            }
            return project(row);
        });
    }
    @Override public Acceptance getAcceptance(String id) {
        store.checkAvailable(); var row = store.dao().acceptance(id);
        return row == null || row.dataEpoch != store.currentEpoch() ? null : project(row);
    }
    private static Acceptance project(ScheduleAcceptanceEntity row) { return new Acceptance(row.runtimeRequestId, row.executionHandle, row.state, row.result, row.reason); }
}
