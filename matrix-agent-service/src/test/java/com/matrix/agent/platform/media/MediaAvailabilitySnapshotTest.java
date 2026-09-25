package com.matrix.agent.platform.media;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Map;

public final class MediaAvailabilitySnapshotTest {
    @Test public void projectsAbsentUnknownAndAmbiguousSessionsConservatively() {
        MediaAvailabilitySnapshot absent = MediaAvailabilitySnapshot.fromFacts(
                Map.of(MediaApp.QQMUSIC, false, MediaApp.BILIBILI, true), null, 5L);
        assertEquals(MediaAvailabilitySnapshot.State.ABSENT,
                absent.activeSession(MediaApp.QQMUSIC));
        assertEquals(MediaAvailabilitySnapshot.State.UNKNOWN,
                absent.activeSession(MediaApp.BILIBILI));

        MediaAvailabilitySnapshot ambiguous = MediaAvailabilitySnapshot.fromFacts(
                Map.of(MediaApp.QQMUSIC, true, MediaApp.BILIBILI, true),
                Map.of(MediaApp.QQMUSIC, 2, MediaApp.BILIBILI, 1), 6L);
        assertEquals(MediaAvailabilitySnapshot.State.UNKNOWN,
                ambiguous.activeSession(MediaApp.QQMUSIC));
        assertEquals(MediaAvailabilitySnapshot.State.AVAILABLE,
                ambiguous.activeSession(MediaApp.BILIBILI));
        assertEquals(6L, ambiguous.capturedAtMillis());
    }

    @Test public void refreshesExpiredAndClockRewoundHints() {
        MediaAvailabilitySnapshot snapshot = MediaAvailabilitySnapshot.fromFacts(
                Map.of(MediaApp.QQMUSIC, true), null, 10_000L);
        assertEquals(false, MediaAvailabilityCache.shouldRefresh(snapshot, 14_999L));
        assertEquals(true, MediaAvailabilityCache.shouldRefresh(snapshot, 15_000L));
        assertEquals(true, MediaAvailabilityCache.shouldRefresh(snapshot, 9_999L));
        assertEquals(true, MediaAvailabilityCache.shouldRefresh(
                MediaAvailabilitySnapshot.unknown(), 1L));
    }
}
