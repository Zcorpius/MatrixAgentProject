package com.matrix.agent.platform.control;

/**
 * Host-owned Android system control boundary.
 *
 * <p>Capabilities express values as normalized percentages so the task domain never depends on
 * device-specific stream ranges or display backlight steps. Implementations must always read back
 * after a write and return whether the requested value was actually observed.
 */
public interface SystemControlPort {
    ControlResult setMediaVolumePercent(int percent);

    ControlResult setScreenBrightnessPercent(int percent);

    /** Immutable write/readback result; diagnostics are safe operational text, never user input. */
    final class ControlResult {
        public final boolean accepted;
        public final boolean verified;
        public final int requestedPercent;
        public final int actualPercent;
        public final String diagnostic;

        private ControlResult(boolean accepted, boolean verified, int requestedPercent,
                int actualPercent, String diagnostic) {
            this.accepted = accepted;
            this.verified = verified;
            this.requestedPercent = requestedPercent;
            this.actualPercent = actualPercent;
            this.diagnostic = diagnostic == null ? "" : diagnostic;
        }

        public static ControlResult readback(int requestedPercent, int actualPercent,
                boolean verified) {
            return new ControlResult(true, verified, requestedPercent, actualPercent, "");
        }

        public static ControlResult rejected(int requestedPercent, String diagnostic) {
            return new ControlResult(false, false, requestedPercent, -1, diagnostic);
        }
    }
}
