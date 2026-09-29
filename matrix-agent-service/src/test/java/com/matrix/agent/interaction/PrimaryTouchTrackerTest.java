package com.matrix.agent.interaction;

import com.matrix.agent.api.interaction.OverlayPointerSample;
import org.junit.Test;
import static org.junit.Assert.*;

public final class PrimaryTouchTrackerTest {
    private final PrimaryTouchTracker tracker = new PrimaryTouchTracker();
    private OverlayPointerSample event(int action, int index, int rotation, int... ids) {
        float[] xs = new float[ids.length], ys = new float[ids.length];
        for (int i = 0; i < ids.length; i++) { xs[i] = ids[i] * 100; ys[i] = ids[i] * 200; }
        return tracker.accept(action, index, 100, 120, ids, xs, ys, rotation);
    }
    @Test public void primaryIdentitySurvivesPointerReorderingAndEndsWithoutTransfer() {
        assertEquals(OverlayPointerSample.DOWN, event(0, 0, 0, 3).phase());
        assertNull(event(5, 1, 0, 3, 7));
        var move = event(2, 0, 0, 7, 3);
        assertEquals(300f, move.x(), 0f);
        assertEquals(600f, move.y(), 0f);
        assertEquals(100L, move.gestureId());
        assertEquals(OverlayPointerSample.UP, event(6, 1, 0, 7, 3).phase());
        assertNull(event(2, 0, 0, 7));
        assertNull(event(1, 0, 0, 7));
        assertEquals(OverlayPointerSample.DOWN, event(0, 0, 0, 7).phase());
    }
    @Test public void secondaryUpDoesNotEndPrimaryAndOrdinaryUpEndsIt() {
        event(0, 0, 0, 3);
        assertNull(event(6, 1, 0, 3, 7));
        assertEquals(OverlayPointerSample.MOVE, event(2, 0, 0, 3).phase());
        assertEquals(OverlayPointerSample.UP, event(1, 0, 0, 3).phase());
        assertNull(tracker.cancel(130));
    }
    @Test public void cancellationMissingPointerAndRotationInvalidateGesture() {
        assertNull(event(2, 0, 0, 3));
        event(0, 0, 0, 3);
        assertEquals(OverlayPointerSample.CANCEL, event(3, 0, 0, 3).phase());
        event(0, 0, 0, 3);
        assertEquals(OverlayPointerSample.CANCEL, event(2, 0, 0, 7).phase());
        event(0, 0, 0, 3);
        assertEquals(OverlayPointerSample.CANCEL, event(2, 0, 1, 3).phase());
        assertNull(event(2, 0, 1, 3));
    }
}
