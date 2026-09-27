package com.matrix.agent.schedule.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.zone.ZoneOffsetTransition;
import java.util.List;
import java.util.Optional;

/** Pure, bounded calculations. Provider access, cursor mutations and misfire decisions stay outside. */
public final class OccurrenceCalculator {
    private OccurrenceCalculator() { }

    public static Optional<Occurrence> next(TimeRule rule, Instant afterExclusive,
            long ruleGeneration, String fixedKey, ClockSample clock) {
        if (ruleGeneration < 0) throw new IllegalArgumentException("invalid rule generation");
        if (rule instanceof TimeRule.Once once) {
            return once.at().isAfter(afterExclusive)
                    ? Optional.of(at(fixedKey, once.at(), clock)) : Optional.empty();
        }
        if (rule instanceof TimeRule.AfterDelay delay) {
            Instant target = delay.bootId().equals(clock.bootId())
                    ? clock.wall().plusMillis(Math.subtractExact(delay.elapsedDeadline(), clock.elapsedMillis()))
                    : delay.wallDeadline();
            return target.isAfter(afterExclusive)
                    ? Optional.of(new Occurrence(fixedKey, target, delay.bootId().equals(clock.bootId())
                            ? delay.elapsedDeadline() : clock.elapsedAt(target)))
                    : Optional.empty();
        }
        if (rule instanceof TimeRule.Recurring recurring) {
            ZoneId zone = recurring.followDeviceZone() ? clock.deviceZone() : recurring.zone();
            LocalDate date = afterExclusive.atZone(zone).toLocalDate();
            if (date.isBefore(recurring.start())) date = recurring.start();
            // A weekly rule always has a candidate within eight dates, including today's elapsed slot.
            for (int i = 0; i < 8; i++, date = date.plusDays(1)) {
                if (recurring.end() != null && date.isAfter(recurring.end())) return Optional.empty();
                if (!recurring.weekdays().isEmpty() && !recurring.weekdays().contains(date.getDayOfWeek())) continue;
                ZonedDateTime resolved = resolve(date.atTime(recurring.time()), zone);
                if (resolved.toInstant().isAfter(afterExclusive)) {
                    String key = ruleGeneration + ":" + date + "T" + recurring.time()
                            + ":" + resolved.getOffset().getTotalSeconds();
                    return Optional.of(at(key, resolved.toInstant(), clock));
                }
            }
            return Optional.empty();
        }
        throw new IllegalArgumentException("calendar occurrences require the authorized instance resolver");
    }

    /** Latest due slot in constant work even after years offline; omitted older slots are summarized. */
    public static Optional<Occurrence> latestDue(TimeRule.Recurring rule, Instant now,
            long generation, ClockSample clock) {
        ZoneId zone = rule.followDeviceZone() ? clock.deviceZone() : rule.zone();
        LocalDate date = now.atZone(zone).toLocalDate();
        if (rule.end() != null && date.isAfter(rule.end())) date = rule.end();
        for (int i = 0; i < 8 && !date.isBefore(rule.start()); i++, date = date.minusDays(1)) {
            if (!rule.weekdays().isEmpty() && !rule.weekdays().contains(date.getDayOfWeek())) continue;
            ZonedDateTime resolved = resolve(date.atTime(rule.time()), zone);
            if (!resolved.toInstant().isAfter(now)) {
                return Optional.of(at(generation + ":" + date + "T" + rule.time() + ":"
                        + resolved.getOffset().getTotalSeconds(), resolved.toInstant(), clock));
            }
        }
        return Optional.empty();
    }

    static ZonedDateTime resolve(LocalDateTime local, ZoneId zone) {
        List<ZoneOffset> offsets = zone.getRules().getValidOffsets(local);
        if (!offsets.isEmpty()) return ZonedDateTime.ofLocal(local, zone, offsets.get(0));
        ZoneOffsetTransition transition = zone.getRules().getTransition(local);
        // A gap goes to its first valid instant, not local+gapDuration (which would shift 02:30 to 03:30).
        return transition.getDateTimeAfter().atZone(zone);
    }

    private static Occurrence at(String key, Instant target, ClockSample clock) {
        return new Occurrence(key, target, clock.elapsedAt(target));
    }
}
