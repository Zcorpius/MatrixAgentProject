package com.matrix.agent.schedule.execution;

import com.matrix.agent.api.schedule.ScheduleSpec;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.VehicleZone;

/** Durable idempotent acceptance is separate from starting work. Request IDs survive lost replies. */
public interface RuntimeExecutionPort {
    record AuthorizationSnapshot(String encoded, Actor actor, VehicleZone zone) { }
    record ExecutionContext(String stepId, long dataEpoch, long expiresAt) { }
    record Acceptance(String runtimeRequestId, String executionHandle, int state, String result, String reason) { }
    Acceptance accept(String runtimeRequestId, String runId, ScheduleSpec executionSpec,
            AuthorizationSnapshot authorizationSnapshot, ExecutionContext context);
    Acceptance getAcceptance(String runtimeRequestId);
}
