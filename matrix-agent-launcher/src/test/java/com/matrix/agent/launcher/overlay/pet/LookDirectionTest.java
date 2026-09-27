package com.matrix.agent.launcher.overlay.pet;

import org.junit.Test;
import static org.junit.Assert.*;

public final class LookDirectionTest {
    private int at(double degrees, int previous) {
        double radians = Math.toRadians(degrees);
        return LookDirection.select((float) (100 * Math.sin(radians)),
                (float) (-100 * Math.cos(radians)), 12, previous);
    }
    @Test public void allSixteenDirectionsAreClockwiseFromUp() {
        for (int i = 0; i < 16; i++) assertEquals(i, at(i * 22.5, -1));
        assertEquals(0, at(359, -1));
        assertEquals(4, LookDirection.select(100, 0, 12, -1));
        assertEquals(8, LookDirection.select(0, 100, 12, -1));
        assertEquals(12, LookDirection.select(-100, 0, 12, -1));
    }
    @Test public void hysteresisPreventsFlickerAcrossSectorBoundariesAndWrap() {
        assertEquals(0, at(12, 0));
        assertEquals(1, at(15, 0));
        assertEquals(1, at(10, 1));
        assertEquals(0, at(8, 1));
        assertEquals(0, at(348, 0));
        assertEquals(15, at(345, 0));
    }
    @Test public void centerAndInvalidCoordinatesUseNeutral() {
        assertEquals(-1, LookDirection.select(0, 0, 12, 4));
        assertEquals(-1, LookDirection.select(12, 0, 12, 4));
        assertEquals(4, LookDirection.select(13, 0, 12, 4));
        assertEquals(-1, LookDirection.select(Float.NaN, 10, 12, -1));
        assertEquals(-1, LookDirection.select(10, Float.POSITIVE_INFINITY, 12, -1));
    }
}
