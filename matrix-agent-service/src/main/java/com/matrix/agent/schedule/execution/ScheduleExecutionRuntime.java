package com.matrix.agent.schedule.execution;

public interface ScheduleExecutionRuntime {
    void execute(String runId, Runnable completion);
    void interruptAll();
    interface Provider { ScheduleExecutionRuntime scheduleExecutionRuntime(); }
}
