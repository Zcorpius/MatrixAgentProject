package com.matrix.agent.data.memory;

import java.util.Objects;

/** Immutable preference projection with the ordering metadata retained by the store. */
public final class PreferenceRecord {
    private final String key;
    private final String value;
    private final long capturedAtMs;
    private final boolean explicit;

    public PreferenceRecord(String key, String value, long capturedAtMs, boolean explicit) {
        this.key = Objects.requireNonNull(key);
        this.value = Objects.requireNonNull(value);
        this.capturedAtMs = capturedAtMs;
        this.explicit = explicit;
    }

    public String key() { return key; }
    public String value() { return value; }
    public long capturedAtMs() { return capturedAtMs; }
    public boolean explicit() { return explicit; }
}
