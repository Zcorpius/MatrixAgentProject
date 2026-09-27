package com.matrix.agent.api.handoff;

/** Frozen wire values. Presentation acknowledgement never authorizes an external side effect. */
public final class HandoffProtocol {
    private HandoffProtocol() {}
    public static final int LAUNCH_ACTIVITY = 1, INTERACT_EXISTING_APP = 2;
    public static final int CREATE_OR_REBIND = 1, REUSE = 2;
    /** Host-only result: durable/debug execution has no conversation presentation binding.
     * Not a Launcher ACK and not evidence of task success. */
    public static final int RESULT_PRESENTATION_SKIPPED = 0;
    public static final int OVERLAY_READY = 1, OVERLAY_PREPARED = 2,
            PRESENTED_IN_LAUNCHER = 3, OVERLAY_UNAVAILABLE = 4, OVERLAY_BUSY = 5,
            USER_DISMISSED = 6, LAUNCHER_NOT_CONNECTED = 7, HANDOFF_TIMED_OUT = 8,
            EXECUTION_CANCELLED = 9, OPERATION_DEADLINE_EXCEEDED = 10;
    public static final int ACCEPTED = 0, EXPIRED = 1, STALE_REGISTRATION = 2,
            ALREADY_RESOLVED = 3;
    public static final int REASON_NONE = 0, PERMISSION_DENIED = 1, WINDOW_FAILED = 2,
            DEVICE_LOCKED = 3, OWNER_STATE_UNAVAILABLE = 4, REUSE_STATE_STALE = 5,
            CONNECTION_LOST = 6;
    public static final int DISPATCHED = 1, DISPATCH_FAILED = 2, DISPATCH_CANCELLED = 3;
    public static final int IDLE = 0, AUTOMATION = 1;
    public static final long PREPARE_TIMEOUT_MS = 1_200, REUSE_TIMEOUT_MS = 50,
            REVEAL_TIMEOUT_MS = 2_000;
    public static final String ACTION_OPEN_CONVERSATION = "com.matrix.agent.action.OPEN_CONVERSATION";
    public static final String EXTRA_CONVERSATION_ID = "conversation_id";
    public static final String EXTRA_MESSAGE_ID = "message_id";

    public static boolean isPresentationReady(int result) {
        return result == OVERLAY_READY || result == OVERLAY_PREPARED
                || result == PRESENTED_IN_LAUNCHER;
    }
    public static boolean isClientResult(int result) {
        return result >= OVERLAY_READY && result <= USER_DISMISSED;
    }
}
