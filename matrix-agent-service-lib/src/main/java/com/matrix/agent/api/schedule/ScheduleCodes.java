package com.matrix.agent.api.schedule;

/** Append-only wire values. Unknown values must be rendered as unknown, never as success. */
public final class ScheduleCodes {
    private ScheduleCodes() { }

    public static final int ONCE = 1, AFTER_DELAY = 2, DAILY = 3, WEEKLY = 4, CALENDAR_OFFSET = 5;
    public static final int NOTIFICATION = 1, AGENT = 2, WORKFLOW = 3, TOOL = 4;
    public static final int SKIP = 1, WITHIN_GRACE = 2, COALESCE_LATEST = 3;

    public static final int DRAFT = 1, ACTIVE = 2, PAUSED = 3, COMPLETED = 4, DELETED = 5;
    public static final int PENDING = 1, ARMED = 2, BLOCKED = 3, ERROR = 4;

    public static final int QUEUED = 1, RUNNING = 2, SUCCEEDED = 3, PARTIAL = 4,
            FAILED = 5, CANCELLED = 6, EXECUTION_UNKNOWN = 7, MISSED = 8, SKIPPED = 9,
            WAITING_TRIGGER = 10, WAITING_CONDITION = 11, CANCEL_REQUESTED = 12,
            WAITING_DEPENDENCY = 13, RETRY_WAIT = 14;
    public static final int DELIVERY_PENDING = 1, DELIVERED = 2, DELIVERY_BLOCKED = 3,
            DELIVERY_FAILED = 4, DELIVERY_UNKNOWN = 5, DELIVERY_NOT_REQUIRED = 6, DELIVERY_PARTIAL = 7;

    public static final int PAUSE = 1, RESUME = 2, DELETE = 3, SKIP_NEXT = 4,
            CANCEL_RUN = 5, PAUSE_AND_CANCEL = 6;

    public static boolean terminalRun(int state) {
        return state >= SUCCEEDED && state <= SKIPPED;
    }
}
