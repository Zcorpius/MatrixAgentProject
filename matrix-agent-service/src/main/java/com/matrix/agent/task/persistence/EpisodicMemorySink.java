package com.matrix.agent.task.persistence;

import com.matrix.agent.identity.ActorUsers;

import com.matrix.agent.data.memory.EpisodicWrite;
import com.matrix.agent.data.memory.MemoryWriter;
import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.task.port.TaskMemoryWriter;

/** 任务终态到 episodic 持久化命令的 task 侧适配器。 */
public final class EpisodicMemorySink implements TaskMemoryWriter {
    private final MemoryWriter writer;

    public EpisodicMemorySink(MemoryWriter writer) {
        this.writer = writer == null ? MemoryWriter.NOOP : writer;
    }

    @Override
    public void writeEpisodicOnTerminal(AgentRequest request, AgentOutcome outcome, long requestEpoch) {
        if (request == null || outcome == null) return;
        com.matrix.agent.task.persistence.EpisodicSummary summary =
                com.matrix.agent.task.persistence.EpisodicSummary.build(request, outcome);
        if (summary.shouldSkip()) return;
        writer.writeEpisodic(request, new EpisodicWrite(
                outcome.getRequestId(), com.matrix.agent.identity.ActorUsers.userIdOf(request),
                request.getOccupantZone() == null ? "global" : request.getOccupantZone().wireValue(),
                request.getSessionId(), request.getActor() == null ? "" : request.getActor().name(),
                summary.getStartedAtMillis(), summary.getFinalState(),
                outcome.getStopReason() == null ? "" : outcome.getStopReason().name(),
                summary.getDurationMs(), summary.getTurnCount(), summary.toJson(), requestEpoch));
    }
}
