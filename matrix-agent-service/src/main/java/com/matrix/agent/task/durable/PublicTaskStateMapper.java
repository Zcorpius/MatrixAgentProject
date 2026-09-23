package com.matrix.agent.task.durable;
import com.matrix.agent.task.*;

import com.matrix.agent.api.common.AgentTaskState;
import com.matrix.agent.task.TaskState;

/** The only allowed mapping from runtime-private states to the frozen SDK state machine. */
final class PublicTaskStateMapper {
    private PublicTaskStateMapper() {}

    static int fromInternal(TaskState state) {
        if (state == null) return AgentTaskState.FAILED;
        switch (state) {
            case SUCCEEDED: return AgentTaskState.COMPLETED;
            case PARTIALLY_SUCCEEDED: return AgentTaskState.PARTIALLY_COMPLETED;
            case CANCELLED:
            case PREEMPTED: return AgentTaskState.CANCELLED;
            case DEFERRED: return AgentTaskState.DEFERRED;
            case EXECUTION_UNKNOWN: return AgentTaskState.EXECUTION_UNKNOWN;
            case REJECTED: return AgentTaskState.REJECTED;
            case RECEIVED:
            case UNDERSTANDING:
            case PLANNED:
            case EXECUTING:
            case VERIFYING: return AgentTaskState.RUNNING;
            case TIMED_OUT:
            case NETWORK_UNAVAILABLE:
            case FAILED:
            default: return AgentTaskState.FAILED;
        }
    }
}
