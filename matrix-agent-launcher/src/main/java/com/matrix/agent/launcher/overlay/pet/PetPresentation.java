package com.matrix.agent.launcher.overlay.pet;

/** Visual intent, independent of both conversation ownership and animation playback position. */
public record PetPresentation(Motion motion, Indicator indicator) {
    public enum Repeat { LOOP, HOLD_LAST, THEN_IDLE }

    public enum Motion {
        NEUTRAL("neutral", Repeat.HOLD_LAST),
        IDLE("idle", Repeat.LOOP),
        WAITING("waiting", Repeat.LOOP),
        WORKING("running", Repeat.LOOP),
        RUN_LEFT("running-left", Repeat.LOOP),
        RUN_RIGHT("running-right", Repeat.LOOP),
        SUCCEEDED("jumping", Repeat.THEN_IDLE),
        FAILED("failed", Repeat.HOLD_LAST),
        REVIEW("review", Repeat.LOOP);

        private final String assetKey;
        private final Repeat repeat;

        Motion(String assetKey, Repeat repeat) {
            this.assetKey = assetKey;
            this.repeat = repeat;
        }

        public String assetKey() { return assetKey; }
        public Repeat repeat() { return repeat; }
    }

    public enum Indicator { NONE, SUCCESS, ERROR, UNCERTAIN, CANCELLED, OFFLINE }
}
