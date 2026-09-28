package com.matrix.agent.task.port;

import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.identity.AgentRequest;

/** Task 终态写入 Episodic memory 的端口。 */
public interface TaskMemoryWriter {
    TaskMemoryWriter NOOP = (request, outcome, epoch) -> { };

    void writeEpisodicOnTerminal(AgentRequest request, AgentOutcome outcome, long requestEpoch);

    /** Each optional terminal observer has an independent failure boundary. */
    default TaskMemoryWriter andThen(TaskMemoryWriter other) {
        return (request, outcome, epoch) -> {
            try { writeEpisodicOnTerminal(request, outcome, epoch); }
            catch (RuntimeException ignored) { }
            try { other.writeEpisodicOnTerminal(request, outcome, epoch); }
            catch (RuntimeException ignored) { }
        };
    }
}
