package com.matrix.agent.schedule.domain;

import java.time.Instant;
import java.util.Objects;

/** Business identity is independent of the replaceable system alarm and its registration generation. */
public record Occurrence(String key, Instant scheduledAt, long elapsedDeadline) {
    public Occurrence {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("occurrence key required");
        Objects.requireNonNull(scheduledAt);
        if (elapsedDeadline < 0) throw new IllegalArgumentException("invalid elapsed deadline");
    }
}
