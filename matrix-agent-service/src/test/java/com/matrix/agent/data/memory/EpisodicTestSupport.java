package com.matrix.agent.data.memory;

import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.task.persistence.EpisodicSummary;
import com.matrix.agent.identity.ActorUsers;
import com.matrix.agent.identity.AgentRequest;

/** Test-only projection helper kept field-for-field aligned with EpisodicMemorySink. */
final class EpisodicTestSupport {
    private EpisodicTestSupport() {}

    static EpisodicWrite write(AgentRequest request, AgentOutcome outcome, long epoch) {
        if (request == null || outcome == null) return null;
        EpisodicSummary summary = EpisodicSummary.build(request, outcome);
        if (summary.shouldSkip()) return null;
        return new EpisodicWrite(
                outcome.getRequestId(), ActorUsers.userIdOf(request),
                request.getOccupantZone() == null ? "" : request.getOccupantZone().name(),
                request.getSessionId(), request.getActor() == null ? "" : request.getActor().name(),
                summary.getStartedAtMillis(), summary.getFinalState(),
                outcome.getStopReason() == null ? "" : outcome.getStopReason().name(),
                summary.getDurationMs(), summary.getTurnCount(), summary.toJson(), epoch);
    }
}
