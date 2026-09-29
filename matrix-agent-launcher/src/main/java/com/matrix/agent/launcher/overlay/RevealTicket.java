package com.matrix.agent.launcher.overlay;

import static com.matrix.agent.api.handoff.HandoffProtocol.REVEAL_TIMEOUT_MS;

/** Two-event rendezvous. A callback can reveal an existing window, never create one. */
public final class RevealTicket {
    private final String requestId;
    private final long bindingVersion;
    private final long operationDeadline;
    private long revealDeadline;
    private boolean dispatched;
    public RevealTicket(String requestId, long bindingVersion, long operationDeadline) {
        this.requestId = requestId; this.bindingVersion = bindingVersion;
        this.operationDeadline = operationDeadline; revealDeadline = operationDeadline;
    }
    public boolean matches(String id, long version) {
        return requestId.equals(id) && version == bindingVersion;
    }
    public void dispatched(long time) {
        dispatched = true; revealDeadline = Math.min(operationDeadline, time + REVEAL_TIMEOUT_MS);
    }
    public boolean canReveal(long now, boolean samePageVisible) {
        return dispatched && !samePageVisible && now < revealDeadline;
    }
    public boolean expired(long now) { return now >= revealDeadline; }
    public long deadline() { return revealDeadline; }
}
