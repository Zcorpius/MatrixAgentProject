package com.matrix.agent.schedule.domain;

/** First admission is not a retry. Retry state must be persisted per candidate before another attempt. */
public final class ContinuationPolicy {
    private static final long BASE_DELAY_MILLIS = 1_000;
    private static final long MAX_DELAY_MILLIS = 60_000;

    private ContinuationPolicy() { }

    public static long nextAttemptAt(long originallyDueAt, long now, int attemptedCount) {
        if (attemptedCount < 0) throw new IllegalArgumentException("negative attempt count");
        if (attemptedCount == 0) return originallyDueAt;
        long delay = Math.min(MAX_DELAY_MILLIS, BASE_DELAY_MILLIS << Math.min(6, attemptedCount - 1));
        return Math.addExact(now, delay);
    }

    /** Null denotes absence, never a sentinel timestamp which can accidentally win the minimum. */
    public static Long armTarget(Long futureOccurrence, Long continuation) {
        if (futureOccurrence == null) return continuation;
        if (continuation == null) return futureOccurrence;
        return Math.min(futureOccurrence, continuation);
    }
}
