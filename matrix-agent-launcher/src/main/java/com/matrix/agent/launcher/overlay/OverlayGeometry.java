package com.matrix.agent.launcher.overlay;

/** Pixel geometry independent of WindowManager and density. Available space is a hard cap. */
final class OverlayGeometry {
    private OverlayGeometry() {}
    static int clampPosition(int preferred, int displaySize, int windowSize, int before, int after) {
        int end = Math.max(0, displaySize - windowSize - Math.max(0, after));
        int start = Math.min(Math.max(0, before), end);
        return Math.max(start, Math.min(preferred, end));
    }
    static int panelHeight(int displayHeight, int imeBottom, int margin) {
        int available = Math.max(0, displayHeight - Math.max(0, imeBottom) - Math.max(0, margin));
        return Math.min(Math.max(0, (int) (displayHeight * .60f)), available);
    }
}
