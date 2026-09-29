package com.matrix.agent.schedule.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/** A single sampling boundary prevents mixing wall time from one instant with elapsed from another. */
public record ClockSample(Instant wall, long elapsedMillis, String bootId, ZoneId deviceZone) {
    public ClockSample {
        Objects.requireNonNull(wall);
        Objects.requireNonNull(deviceZone);
        if (elapsedMillis < 0 || bootId == null || bootId.isBlank()) {
            throw new IllegalArgumentException("invalid clock sample");
        }
    }

    public long elapsedAt(Instant target) {
        long remaining = Math.max(0L, Math.subtractExact(target.toEpochMilli(), wall.toEpochMilli()));
        return Math.addExact(elapsedMillis, remaining);
    }
}
