package com.matrix.agent.schedule.domain;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Set;

/** Normalized time semantics. Android registration and wall-clock recovery are separate concerns. */
public sealed interface TimeRule {
    record Once(Instant at) implements TimeRule {
        public Once { Objects.requireNonNull(at); }
    }

    /** The fixed occurrence identity is owned by the definition, never derived from this deadline. */
    record AfterDelay(Instant wallDeadline, long elapsedDeadline, String bootId) implements TimeRule {
        public AfterDelay {
            Objects.requireNonNull(wallDeadline);
            if (elapsedDeadline < 0 || bootId == null || bootId.isBlank()) {
                throw new IllegalArgumentException("invalid relative deadline");
            }
        }
    }

    /** Empty weekdays means daily; a nonempty immutable set means weekly. End date is inclusive. */
    record Recurring(LocalTime time, ZoneId zone, Set<DayOfWeek> weekdays,
            LocalDate start, LocalDate end, boolean followDeviceZone) implements TimeRule {
        public Recurring {
            Objects.requireNonNull(time);
            Objects.requireNonNull(zone);
            Objects.requireNonNull(start);
            weekdays = Set.copyOf(weekdays);
            if (end != null && end.isBefore(start)) throw new IllegalArgumentException("end before start");
            if (time.getSecond() != 0 || time.getNano() != 0) {
                throw new IllegalArgumentException("recurrence precision is one minute");
            }
        }
    }

    /** The calendar adapter resolves the stable original instance before scheduling it. */
    record CalendarOffset(String bindingId, long offsetMillis) implements TimeRule {
        public CalendarOffset {
            if (bindingId == null || bindingId.isBlank() || offsetMillis < 0) {
                throw new IllegalArgumentException("invalid calendar binding");
            }
        }
    }
}
