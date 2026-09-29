package com.matrix.agent.interaction;

import com.matrix.agent.api.common.ParcelSchema;
import com.matrix.agent.api.interaction.OverlayPointerSample;

/** One primary pointer per gesture; lifting it never transfers attention to a remaining finger. */
public final class PrimaryTouchTracker {
    private int pointerId = -1, rotation;
    private long gestureId;
    private float x, y;
    public OverlayPointerSample accept(int action, int actionIndex, long downTime, long time,
            int[] ids, float[] xs, float[] ys, int rotation) {
        if (action == 0) { pointerId = ids[0]; gestureId = downTime; this.rotation = rotation; }
        if (pointerId == -1) return null;
        if (this.rotation != rotation || action == 3) return cancel(time);
        int index = -1;
        for (int i = 0; i < ids.length; i++) if (ids[i] == pointerId) { index = i; break; }
        if (index < 0) return cancel(time);
        x = xs[index]; y = ys[index];
        int phase;
        if (action == 0) phase = OverlayPointerSample.DOWN;
        else if (action == 2) phase = OverlayPointerSample.MOVE;
        else if (action == 1 || action == 6 && ids[actionIndex] == pointerId) {
            phase = OverlayPointerSample.UP; pointerId = -1;
        } else return null;
        return sample(phase, time);
    }
    public OverlayPointerSample cancel(long time) {
        if (pointerId == -1) return null;
        pointerId = -1;
        return sample(OverlayPointerSample.CANCEL, time);
    }
    private OverlayPointerSample sample(int phase, long time) {
        return new OverlayPointerSample(ParcelSchema.CURRENT, gestureId, phase, x, y, rotation, time);
    }
}
