package com.matrix.agent.platform.control;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Pure conversion tests keep percentage semantics stable across Android display implementations. */
public final class AndroidSystemControlAdapterTest {
    @Test public void brightnessPercentageMappingPreservesBoundsAndRoundTrips() {
        assertEquals(4, AndroidSystemControlAdapter.percentToBrightness(1));
        assertEquals(255, AndroidSystemControlAdapter.percentToBrightness(100));
        assertEquals(1, AndroidSystemControlAdapter.brightnessToPercent(4));
        assertEquals(100, AndroidSystemControlAdapter.brightnessToPercent(255));
    }
}
