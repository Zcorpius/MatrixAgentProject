package com.matrix.agent.launcher.presentation;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Monotonic intent token for a screen-level asynchronous state machine.
 *
 * <p>Binder and network completions are allowed to arrive out of order.  A ViewModel captures
 * the value returned from {@link #begin()} when it starts a user intent and accepts a completion
 * only while that value is current.  This keeps an older request from repainting a newer screen
 * without cancelling work that may already have been accepted by the Host.</p>
 */
public final class OperationEpoch {
    private final AtomicLong value = new AtomicLong();

    /** Starts a new user intent and invalidates all earlier completions. */
    public long begin() {
        return value.incrementAndGet();
    }

    /** Captures the current intent for a read that must not outlive a later user command. */
    public long current() {
        return value.get();
    }

    public boolean isCurrent(long candidate) {
        return candidate == value.get();
    }
}
