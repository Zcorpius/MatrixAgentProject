package com.matrix.agent.launcher.overlay.pet;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public final class SpriteTimelineTest {
    private final SpriteTimeline idle = new SpriteTimeline(List.of(280, 110, 110, 140, 140, 320));

    @Test public void unequalFrameDurationsUseExclusiveEndBoundaries() {
        assertEquals(new SpriteTimeline.Sample(0, 280, false), idle.sample(0, true));
        assertEquals(new SpriteTimeline.Sample(0, 1, false), idle.sample(279, true));
        assertEquals(new SpriteTimeline.Sample(1, 110, false), idle.sample(280, true));
        assertEquals(new SpriteTimeline.Sample(5, 1, false), idle.sample(1099, true));
        assertEquals(new SpriteTimeline.Sample(0, 280, false), idle.sample(1100, true));
    }

    @Test public void delayedCallbacksSkipToTheCorrectFrameWithoutClockDrift() {
        assertEquals(new SpriteTimeline.Sample(3, 60, false), idle.sample(1100L * 100_000 + 580, true));
        assertEquals(new SpriteTimeline.Sample(0, 280, false), idle.sample(-10, true));
    }

    @Test public void oneShotShowsTheLastFrameForItsEntireDurationThenStops() {
        assertEquals(new SpriteTimeline.Sample(5, 320, false), idle.sample(780, false));
        assertEquals(new SpriteTimeline.Sample(5, 0, true), idle.sample(1100, false));
        assertEquals(new SpriteTimeline.Sample(5, 0, true), idle.sample(Long.MAX_VALUE, false));
    }

    @Test public void invalidTimingsCannotCreateBusyLoopsOrOverflow() {
        assertThrows(IllegalArgumentException.class, () -> new SpriteTimeline(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new SpriteTimeline(List.of(100, 0)));
        assertThrows(IllegalArgumentException.class, () -> new SpriteTimeline(List.of(-1)));
        assertThrows(ArithmeticException.class, () -> new SpriteTimeline(List.of(Integer.MAX_VALUE, 1)));
    }
}
