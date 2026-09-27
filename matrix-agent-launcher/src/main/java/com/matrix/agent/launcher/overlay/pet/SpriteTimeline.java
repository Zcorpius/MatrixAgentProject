package com.matrix.agent.launcher.overlay.pet;

import java.util.List;

/** Samples visible elapsed time; late UI callbacks skip frames instead of slowing the animation. */
final class SpriteTimeline {
    record Sample(int frame, long nextFrameInMs, boolean finished) {}

    private final int[] ends;
    private final int durationMs;

    SpriteTimeline(List<Integer> durations) {
        if (durations.isEmpty()) throw new IllegalArgumentException("Empty animation");
        ends = new int[durations.size()];
        int total = 0;
        for (int i = 0; i < ends.length; i++) {
            int duration = durations.get(i);
            if (duration <= 0) throw new IllegalArgumentException("Non-positive frame duration");
            total = Math.addExact(total, duration);
            ends[i] = total;
        }
        durationMs = total;
    }

    int durationMs() { return durationMs; }

    Sample sample(long elapsedMs, boolean loop) {
        long elapsed = Math.max(0, elapsedMs);
        if (!loop && elapsed >= durationMs) return new Sample(ends.length - 1, 0, true);
        int position = (int) (elapsed % durationMs);
        for (int i = 0; i < ends.length; i++) {
            if (position < ends[i]) return new Sample(i, ends[i] - position, false);
        }
        throw new AssertionError("Animation position outside duration");
    }
}
