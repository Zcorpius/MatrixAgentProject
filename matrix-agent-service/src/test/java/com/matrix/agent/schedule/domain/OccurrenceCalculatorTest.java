package com.matrix.agent.schedule.domain;

import static org.junit.Assert.*;

import org.junit.Test;

import java.time.*;
import java.util.Set;

public final class OccurrenceCalculatorTest {
    private static ClockSample clock(String instant, long elapsed, String boot) {
        return new ClockSample(Instant.parse(instant), elapsed, boot, ZoneId.of("Asia/Shanghai"));
    }

    @Test public void delayKeepsElapsedDeadlineWhenWallClockMoves() {
        var rule = new TimeRule.AfterDelay(Instant.parse("2026-09-27T01:00:00Z"), 60_000, "boot-a");
        var sample = clock("2026-09-27T03:00:00Z", 30_000, "boot-a");
        var occurrence = OccurrenceCalculator.next(rule, sample.wall().minusMillis(1), 1, "fixed", sample).orElseThrow();
        assertEquals(Instant.parse("2026-09-27T03:00:30Z"), occurrence.scheduledAt());
        assertEquals(60_000, occurrence.elapsedDeadline());
        assertEquals("fixed", occurrence.key());
    }

    @Test public void rebootRecoversWallTargetAndKeepsIdentity() {
        var rule = new TimeRule.AfterDelay(Instant.parse("2026-09-27T01:00:00Z"), 60_000, "boot-a");
        var sample = clock("2026-09-27T00:59:00Z", 5_000, "boot-b");
        var occurrence = OccurrenceCalculator.next(rule, sample.wall(), 99, "fixed", sample).orElseThrow();
        assertEquals(rule.wallDeadline(), occurrence.scheduledAt());
        assertEquals(65_000, occurrence.elapsedDeadline());
        assertEquals("fixed", occurrence.key());
    }

    @Test public void gapUsesFirstValidInstantAndOverlapUsesEarlierOffsetOnce() {
        var zone = ZoneId.of("America/New_York");
        var gap = new TimeRule.Recurring(LocalTime.of(2, 30), zone, Set.of(), LocalDate.of(2026, 3, 1), null, false);
        var beforeGap = clock("2026-03-08T00:00:00Z", 0, "boot");
        assertEquals(Instant.parse("2026-03-08T07:00:00Z"),
                OccurrenceCalculator.next(gap, beforeGap.wall(), 3, "", beforeGap).orElseThrow().scheduledAt());
        var overlap = new TimeRule.Recurring(LocalTime.of(1, 30), zone, Set.of(), LocalDate.of(2026, 11, 1), null, false);
        var beforeOverlap = clock("2026-11-01T00:00:00Z", 0, "boot");
        var first = OccurrenceCalculator.next(overlap, beforeOverlap.wall(), 3, "", beforeOverlap).orElseThrow();
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), first.scheduledAt());
        assertEquals(Instant.parse("2026-11-02T06:30:00Z"),
                OccurrenceCalculator.next(overlap, first.scheduledAt(), 3, "", beforeOverlap).orElseThrow().scheduledAt());
    }

    @Test public void weeklyBoundariesAndYearsOfMisfiresAreBounded() {
        var rule = new TimeRule.Recurring(LocalTime.of(8, 0), ZoneId.of("Asia/Shanghai"),
                Set.of(DayOfWeek.MONDAY), LocalDate.of(2020, 1, 1), LocalDate.of(2026, 9, 28), false);
        var sample = clock("2026-09-27T01:00:00Z", 0, "boot");
        assertEquals(Instant.parse("2026-09-28T00:00:00Z"),
                OccurrenceCalculator.next(rule, sample.wall(), 1, "", sample).orElseThrow().scheduledAt());
        assertEquals(Instant.parse("2026-09-21T00:00:00Z"),
                OccurrenceCalculator.latestDue(rule, sample.wall(), 1, sample).orElseThrow().scheduledAt());
        assertTrue(OccurrenceCalculator.next(rule, Instant.parse("2026-09-28T00:00:00Z"), 1, "", sample).isEmpty());
    }

    @Test public void fixedZoneDoesNotFollowDeviceButOptInDoes() {
        var fixed = new TimeRule.Recurring(LocalTime.of(8, 0), ZoneId.of("UTC"), Set.of(), LocalDate.of(2026, 9, 27), null, false);
        var following = new TimeRule.Recurring(fixed.time(), fixed.zone(), fixed.weekdays(), fixed.start(), null, true);
        var sample = clock("2026-09-27T00:00:00Z", 0, "boot");
        assertEquals(Instant.parse("2026-09-27T08:00:00Z"), OccurrenceCalculator.next(fixed, sample.wall(), 1, "", sample).orElseThrow().scheduledAt());
        assertEquals(Instant.parse("2026-09-28T00:00:00Z"), OccurrenceCalculator.next(following, sample.wall(), 1, "", sample).orElseThrow().scheduledAt());
    }

    @Test public void continuationUsesOriginalDueOnlyBeforeFirstAttempt() {
        assertEquals(10, ContinuationPolicy.nextAttemptAt(10, 100, 0));
        assertEquals(1_100, ContinuationPolicy.nextAttemptAt(10, 100, 1));
        assertEquals(60_100, ContinuationPolicy.nextAttemptAt(10, 100, Integer.MAX_VALUE));
        assertEquals(Long.valueOf(10), ContinuationPolicy.armTarget(100L, 10L));
        assertEquals(Long.valueOf(10), ContinuationPolicy.armTarget(null, 10L));
        assertEquals(Long.valueOf(10), ContinuationPolicy.armTarget(10L, null));
        assertNull(ContinuationPolicy.armTarget(null, null));
    }
}
