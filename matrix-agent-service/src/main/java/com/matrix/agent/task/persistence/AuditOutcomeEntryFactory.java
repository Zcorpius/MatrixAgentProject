package com.matrix.agent.task.persistence;

import com.matrix.agent.data.audit.AuditOutcomeEntry;
import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.task.Trajectory;
import com.matrix.agent.identity.ActorUsers;
import com.matrix.agent.identity.AgentRequest;

/** Task 领域对象到审计写入命令的唯一投影点。 */
public final class AuditOutcomeEntryFactory {
    private AuditOutcomeEntryFactory() {}

    public static AuditOutcomeEntry from(AgentOutcome outcome, AgentRequest request) {
        if (outcome == null || request == null) return null;
        Trajectory trajectory = outcome.getTrajectory();
        return new AuditOutcomeEntry(
                outcome.getRequestId(), request.getSessionId(), request.getArbitrationKey(),
                request.getActor() == null ? "" : request.getActor().name(),
                request.getOccupantZone() == null ? "" : request.getOccupantZone().name(),
                ActorUsers.userIdOf(request), trajectory.getStartedAtMillis(),
                outcome.getDurationMillis(), trajectory.getIterations().size(),
                trajectory.getTotalToolCalls(), trajectory.countSuccessfulToolCalls(),
                outcome.getStopReason() == null ? "" : outcome.getStopReason().name(),
                outcome.getFinalState() == null ? "" : outcome.getFinalState().name(),
                TrajectoryCodec.encode(outcome));
    }
}
