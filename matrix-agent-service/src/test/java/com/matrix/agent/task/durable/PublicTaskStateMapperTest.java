package com.matrix.agent.task.durable;
import com.matrix.agent.task.durable.*;

import static org.junit.Assert.assertEquals;

import com.matrix.agent.api.common.AgentTaskState;
import com.matrix.agent.task.TaskState;

import org.junit.Test;

/** Locks the runtime-private to frozen SDK state mapping. */
public final class PublicTaskStateMapperTest {

    @Test
    public void everyInternalStateHasAnIntentionalPublicMapping() {
        assertEquals(AgentTaskState.RUNNING,
                PublicTaskStateMapper.fromInternal(TaskState.RECEIVED));
        assertEquals(AgentTaskState.RUNNING,
                PublicTaskStateMapper.fromInternal(TaskState.UNDERSTANDING));
        assertEquals(AgentTaskState.RUNNING,
                PublicTaskStateMapper.fromInternal(TaskState.PLANNED));
        assertEquals(AgentTaskState.RUNNING,
                PublicTaskStateMapper.fromInternal(TaskState.EXECUTING));
        assertEquals(AgentTaskState.RUNNING,
                PublicTaskStateMapper.fromInternal(TaskState.VERIFYING));
        assertEquals(AgentTaskState.COMPLETED,
                PublicTaskStateMapper.fromInternal(TaskState.SUCCEEDED));
        assertEquals(AgentTaskState.PARTIALLY_COMPLETED,
                PublicTaskStateMapper.fromInternal(TaskState.PARTIALLY_SUCCEEDED));
        assertEquals(AgentTaskState.FAILED,
                PublicTaskStateMapper.fromInternal(TaskState.FAILED));
        assertEquals(AgentTaskState.FAILED,
                PublicTaskStateMapper.fromInternal(TaskState.TIMED_OUT));
        assertEquals(AgentTaskState.CANCELLED,
                PublicTaskStateMapper.fromInternal(TaskState.CANCELLED));
        assertEquals(AgentTaskState.CANCELLED,
                PublicTaskStateMapper.fromInternal(TaskState.PREEMPTED));
        assertEquals(AgentTaskState.DEFERRED,
                PublicTaskStateMapper.fromInternal(TaskState.DEFERRED));
        assertEquals(AgentTaskState.EXECUTION_UNKNOWN,
                PublicTaskStateMapper.fromInternal(TaskState.EXECUTION_UNKNOWN));
        assertEquals(AgentTaskState.REJECTED,
                PublicTaskStateMapper.fromInternal(TaskState.REJECTED));
    }

    @Test
    public void absentRuntimeStateFailsClosed() {
        assertEquals(AgentTaskState.FAILED, PublicTaskStateMapper.fromInternal(null));
    }
}
