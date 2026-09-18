package com.matrix.agent.task.port;

import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.identity.AgentRequest;

/** Task 终态写入 Episodic memory 的端口。 */
public interface TaskMemoryWriter {
    TaskMemoryWriter NOOP = (request, outcome, epoch) -> { };

    void writeEpisodicOnTerminal(AgentRequest request, AgentOutcome outcome, long requestEpoch);
}
