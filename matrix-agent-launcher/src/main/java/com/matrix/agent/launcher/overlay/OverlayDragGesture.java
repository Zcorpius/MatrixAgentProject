package com.matrix.agent.launcher.overlay;

/** Single-pointer drag math. Coordinates stay relative to DOWN, even while the window moves. */
final class OverlayDragGesture {
    record Position(int x, int y) {}
    enum Completion { NONE, TAP, DRAG }
    private final int slop;
    private float downX, downY;
    private float directionAnchorX;
    private boolean movingLeft;
    private int originX, originY;
    private boolean active, dragging;

    OverlayDragGesture(int slop) { this.slop = slop; }
    void begin(float x, float y, int windowX, int windowY) {
        downX = x; downY = y; originX = windowX; originY = windowY;
        directionAnchorX = x;
        active = true; dragging = false;
    }
    Position move(float x, float y) {
        if (!active) return null;
        float dx = x - downX, dy = y - downY;
        dragging |= Math.hypot(dx, dy) > slop;
        if (!dragging) return null;
        // Require a full slop of reversal from the horizontal extreme, not from the DOWN point.
        // Vertical travel retains the last facing; small finger tremors cannot flip the sprite.
        if (x - directionAnchorX > slop) movingLeft = false;
        else if (directionAnchorX - x > slop) movingLeft = true;
        directionAnchorX = movingLeft ? Math.min(directionAnchorX, x) : Math.max(directionAnchorX, x);
        return new Position(originX + Math.round(dx), originY + Math.round(dy));
    }
    boolean movingLeft() { return movingLeft; }
    Completion finish() {
        Completion result = !active ? Completion.NONE : dragging ? Completion.DRAG : Completion.TAP;
        cancel();
        return result;
    }
    void cancel() { active = false; dragging = false; }
}
