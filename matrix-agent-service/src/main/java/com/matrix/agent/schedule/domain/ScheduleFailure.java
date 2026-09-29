package com.matrix.agent.schedule.domain;

/** Structured, safe boundary failure. Internal exception messages are never returned as task content. */
public final class ScheduleFailure extends RuntimeException {
    private final int code;
    public ScheduleFailure(int code, String safeReason) { super(safeReason); this.code = code; }
    public int code() { return code; }
}
