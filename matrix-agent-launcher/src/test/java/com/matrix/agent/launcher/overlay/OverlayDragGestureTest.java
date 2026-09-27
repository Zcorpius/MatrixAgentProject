package com.matrix.agent.launcher.overlay;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.matrix.agent.launcher.overlay.OverlayDragGesture.Completion.*;

public final class OverlayDragGestureTest {
    private final OverlayDragGesture gesture = new OverlayDragGesture(8);
    @Test public void touchSlopPreservesTapWithoutMoving() {
        gesture.begin(100, 200, 20, 40);
        assertNull(gesture.move(105, 205));
        assertEquals(TAP, gesture.finish());
    }
    @Test public void dragUsesScreenDeltaFromOriginalWindowPosition() {
        gesture.begin(100, 200, 20, 40);
        assertEquals(new OverlayDragGesture.Position(40, 80), gesture.move(120, 240));
        assertEquals(new OverlayDragGesture.Position(50, 60), gesture.move(130, 220));
        assertEquals(DRAG, gesture.finish());
    }
    @Test public void returningToStartNeverConvertsDragToClick() {
        gesture.begin(100, 200, 20, 40);
        gesture.move(150, 240);
        assertEquals(new OverlayDragGesture.Position(20, 40), gesture.move(100, 200));
        assertEquals(DRAG, gesture.finish());
    }
    @Test public void cancelledOrMultitouchGestureCannotResumeOrClick() {
        gesture.begin(100, 200, 20, 40);
        gesture.move(150, 240);
        gesture.cancel();
        assertNull(gesture.move(160, 240));
        assertEquals(NONE, gesture.finish());
    }
    @Test public void nextGestureUsesFreshPositionAfterClampingOrResize() {
        gesture.begin(100, 200, 20, 40);
        gesture.move(100, 600);
        gesture.finish();
        gesture.begin(100, 200, 8, 24);
        assertEquals(new OverlayDragGesture.Position(18, 54), gesture.move(110, 230));
        assertEquals(DRAG, gesture.finish());
    }
}
