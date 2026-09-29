package com.matrix.agent.schedule.domain;

import static org.junit.Assert.*;
import static com.matrix.agent.api.schedule.ScheduleCodes.*;
import com.matrix.agent.api.schedule.*;
import java.time.*;
import java.util.List;
import org.junit.Test;

public final class ScheduleNormalizerTest {
    private final ClockSample clock = new ClockSample(Instant.parse("2026-09-27T00:00:00Z"), 1000, "boot", ZoneId.of("Asia/Shanghai"));
    private final ScheduleNormalizer normalizer = new ScheduleNormalizer();
    private ScheduleSpec spec(String text) {
        return new ScheduleSpec("测试", new ScheduleTiming(AFTER_DELAY, "Asia/Shanghai", 0, 60_000,
                "", 0, "", "", false, "", 0), new ScheduleAction(NOTIFICATION, text, "", 0, "{}", List.of(), false, false), 600_000, WITHIN_GRACE);
    }
    @Test public void storageRoundTripPreservesDelayIdentityAndUnicode() {
        var normalized = normalizer.normalize(spec("喝水 🌊"), clock);
        assertEquals(normalized.rule(), ScheduleCodec.rule(ScheduleCodec.rule(normalized.rule())));
        String encoded = ScheduleCodec.spec(normalized.spec());
        assertEquals(encoded, ScheduleCodec.spec(ScheduleCodec.spec(encoded)));
        assertEquals(clock.wall().plusSeconds(60), ((TimeRule.AfterDelay) normalized.rule()).wallDeadline());
    }
    @Test public void bothUtf16AndUtf8BoundsAreEnforcedWithoutTruncation() {
        assertEquals(4096, normalizer.normalize(spec("x".repeat(4096)), clock).spec().action.text.length());
        assertThrows(IllegalArgumentException.class, () -> normalizer.normalize(spec("x".repeat(4097)), clock));
        assertThrows(IllegalArgumentException.class, () -> normalizer.normalize(spec("中".repeat(2731)), clock));
        assertThrows(IllegalArgumentException.class, () -> normalizer.normalize(spec("bad\ud800"), clock));
    }
    @Test public void canonicalParametersAndDigestAreUnambiguous() {
        assertEquals(ScheduleCodec.canonicalObject("{\"b\":2,\"a\":{\"d\":4,\"c\":3}}"),
                ScheduleCodec.canonicalObject("{\"a\":{\"c\":3,\"d\":4},\"b\":2}"));
        assertNotEquals(ScheduleCodec.digest("ab", "c"), ScheduleCodec.digest("a", "bc"));
        assertThrows(IllegalArgumentException.class, () -> ScheduleCodec.canonicalObject("[]"));
    }
    @Test public void localNotificationCannotSmuggleExecutionAuthority() {
        ScheduleSpec valid = spec("reminder");
        ScheduleSpec invalid = new ScheduleSpec(valid.title, valid.timing,
                new ScheduleAction(NOTIFICATION, "reminder", "", 0, "{}", List.of("calendar.query"), false, false), valid.graceMillis, valid.misfirePolicy);
        assertThrows(IllegalArgumentException.class, () -> normalizer.normalize(invalid, clock));
    }
    @Test public void malformedUuidAndInvalidMinuteAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ScheduleNormalizer.requireUuid("1-1-1-1-1"));
        ScheduleSpec valid = spec("reminder");
        ScheduleSpec invalid = new ScheduleSpec(valid.title, new ScheduleTiming(DAILY, "UTC", 0, 0,
                "24:00", 0, "", "", false, "", 0), valid.action, valid.graceMillis, valid.misfirePolicy);
        assertThrows(IllegalArgumentException.class, () -> normalizer.normalize(invalid, clock));
    }
}
