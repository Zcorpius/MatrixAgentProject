package com.matrix.agent.schedule.workflow;

/** Pure union-of-active-intervals ledger; queue and retry waits never consume active wall time. */
public final class ActiveBudget {
    private ActiveBudget() { }
    public static long charge(long accumulated, Long activeSince, long monotonicNow) {
        if (accumulated < 0 || monotonicNow < 0) throw new IllegalArgumentException("invalid budget clock");
        return activeSince == null ? accumulated : Math.addExact(accumulated, Math.max(0, monotonicNow - activeSince));
    }
    public static long allowance(long spent, long stepCap, long wallNow, long expiresAt) {
        return allowance(spent, stepCap, wallNow, expiresAt, com.matrix.agent.identity.ExecutionProfile.INTERACTIVE);
    }
    public static long allowance(long spent, long stepCap, long wallNow, long expiresAt,
            com.matrix.agent.identity.ExecutionProfile profile) {
        return Math.max(0, Math.min(Math.min(profile.maxActiveMillis() - spent,
                Math.min(profile.maxStepMillis(), stepCap)), expiresAt - wallNow));
    }
}
