package com.matrix.agent.launcher.overlay;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class OverlayGeometryTest {
    @Test public void ordinaryPanelUsesSixtyPercent() {
        assertEquals(600, OverlayGeometry.panelHeight(1000, 0, 48));
    }
    @Test public void panelDragRespectsAllEdgesAndKeyboardSpace() {
        assertEquals(8, OverlayGeometry.clampPosition(-100, 400, 350, 8, 8));
        assertEquals(42, OverlayGeometry.clampPosition(1000, 400, 350, 8, 8));
        assertEquals(120, OverlayGeometry.clampPosition(120, 800, 400, 24, 24));
        assertEquals(26, OverlayGeometry.clampPosition(500, 450, 400, 24, 24));
        assertEquals(0, OverlayGeometry.clampPosition(500, 300, 400, 24, 24));
    }
    @Test public void smallDisplayAndKeyboardRemainHardLimits() {
        assertEquals(112, OverlayGeometry.panelHeight(400, 240, 48));
        assertEquals(120, OverlayGeometry.panelHeight(200, 0, 48));
        assertEquals(0, OverlayGeometry.panelHeight(200, 250, 48));
    }
}
