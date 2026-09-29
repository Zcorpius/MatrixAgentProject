package com.matrix.agent.schedule;

/** Narrow application boundary for cold Android entry points. */
public interface ScheduleRuntimeProvider {
    ScheduleRuntime scheduleRuntime();
}
