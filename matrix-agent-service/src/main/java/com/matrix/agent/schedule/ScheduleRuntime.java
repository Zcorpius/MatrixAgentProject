package com.matrix.agent.schedule;

public interface ScheduleRuntime {
    /** Completion is always called, including overload and the receiver deadline. */
    void wake(String source, long expectedEpoch, long receivedAt, long receivedElapsed, java.util.function.Consumer<Boolean> completion);
}
