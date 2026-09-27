package com.matrix.agent.platform.media;

/** Stable, non-sensitive platform failure code. */
public final class MediaPlatformException extends Exception {
    private final String code;

    public MediaPlatformException(String code) {
        super(code);
        this.code = code;
    }

    public String code() { return code; }
}
