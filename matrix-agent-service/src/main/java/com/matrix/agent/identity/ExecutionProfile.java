package com.matrix.agent.identity;

/** Host-selected execution ceilings. Model arguments cannot change a request's profile. */
public enum ExecutionProfile {
    INTERACTIVE(8, 8, 60_000, 120_000, 60_000, 16, 8),
    WEATHER(0, 4, 45_000, 45_000, 16_000, 4, 4),
    RESEARCH(40, 40, 30 * 60_000, 30 * 60_000, 3 * 60_000, 40, 16);

    private final int iterations, toolCalls, workflowToolCalls, workflowSteps;
    private final long taskMillis, activeMillis, stepMillis;
    ExecutionProfile(int iterations, int toolCalls, long taskMillis, long activeMillis,
            long stepMillis, int workflowToolCalls, int workflowSteps) {
        this.iterations = iterations; this.toolCalls = toolCalls; this.taskMillis = taskMillis;
        this.activeMillis = activeMillis; this.stepMillis = stepMillis;
        this.workflowToolCalls = workflowToolCalls; this.workflowSteps = workflowSteps;
    }
    public int maxIterations() { return iterations; }
    public int maxToolCalls() { return toolCalls; }
    public long maxTaskMillis() { return taskMillis; }
    public long maxActiveMillis() { return activeMillis; }
    public long maxStepMillis() { return stepMillis; }
    public int maxWorkflowToolCalls() { return workflowToolCalls; }
    public int maxWorkflowSteps() { return workflowSteps; }
}
