package com.matrix.agent.launcher.overlay.pet;

/** 0 = up, clockwise in 22.5-degree steps; -1 is the neutral center deadzone. */
public final class LookDirection {
    private LookDirection() { }
    public static int select(float dx, float dy, float deadzone, int previous) {
        if (!Float.isFinite(dx) || !Float.isFinite(dy) || Math.hypot(dx, dy) <= deadzone) return -1;
        double angle = (Math.toDegrees(Math.atan2(dx, -dy)) + 360) % 360;
        if (previous >= 0 && previous < 16) {
            double delta = Math.abs((angle - previous * 22.5 + 540) % 360 - 180);
            if (delta <= 14.25) return previous;
        }
        return ((int) Math.round(angle / 22.5)) % 16;
    }
}
