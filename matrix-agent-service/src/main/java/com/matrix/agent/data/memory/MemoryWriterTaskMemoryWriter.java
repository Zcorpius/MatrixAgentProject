package com.matrix.agent.data.memory;

import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.task.identity.AgentRequest;
import com.matrix.agent.task.port.TaskMemoryWriter;

/** MemoryWriter 到任务层终态记忆端口的基础设施适配器。 */
public final class MemoryWriterTaskMemoryWriter implements TaskMemoryWriter {
    private final MemoryWriter writer;

    public MemoryWriterTaskMemoryWriter(MemoryWriter writer) {
        this.writer = writer == null ? MemoryWriter.NOOP : writer;
    }

    @Override
    public void writeEpisodicOnTerminal(AgentRequest request, AgentOutcome outcome, long requestEpoch) {
        writer.writeEpisodicOnTerminal(request, outcome, requestEpoch);
    }
}
