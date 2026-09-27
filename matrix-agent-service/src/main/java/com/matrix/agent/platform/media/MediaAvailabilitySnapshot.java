package com.matrix.agent.platform.media;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/** Privacy-minimal hint for skill selection. It never carries titles or identifiers. */
public final class MediaAvailabilitySnapshot {
    public enum State { AVAILABLE, ABSENT, UNKNOWN }

    private final Map<MediaApp, State> installed;
    private final Map<MediaApp, State> sessions;
    private final long capturedAtMillis;

    public MediaAvailabilitySnapshot(Map<MediaApp, State> installed,
            Map<MediaApp, State> sessions, long capturedAtMillis) {
        this.installed = immutable(installed);
        this.sessions = immutable(sessions);
        this.capturedAtMillis = capturedAtMillis;
    }

    public static MediaAvailabilitySnapshot unknown() {
        return new MediaAvailabilitySnapshot(Collections.emptyMap(), Collections.emptyMap(), 0L);
    }

    /** Pure projection of PackageManager and MediaSession facts into privacy-minimal hints. */
    static MediaAvailabilitySnapshot fromFacts(Map<MediaApp, Boolean> installedFacts,
            Map<MediaApp, Integer> sessionCounts, long capturedAtMillis) {
        EnumMap<MediaApp, State> installed = new EnumMap<>(MediaApp.class);
        EnumMap<MediaApp, State> sessions = new EnumMap<>(MediaApp.class);
        for (MediaApp app : MediaApp.values()) {
            Boolean present = installedFacts.get(app);
            installed.put(app, present == null ? State.UNKNOWN
                    : present ? State.AVAILABLE : State.ABSENT);
            Integer count = sessionCounts == null ? null : sessionCounts.get(app);
            sessions.put(app, Boolean.FALSE.equals(present) ? State.ABSENT
                    : count == null ? State.UNKNOWN
                    : count == 0 ? State.ABSENT
                    : count == 1 ? State.AVAILABLE : State.UNKNOWN);
        }
        return new MediaAvailabilitySnapshot(installed, sessions, capturedAtMillis);
    }

    public State installed(MediaApp app) {
        return installed.getOrDefault(app, State.UNKNOWN);
    }

    public State activeSession(MediaApp app) {
        return sessions.getOrDefault(app, State.UNKNOWN);
    }

    public long capturedAtMillis() { return capturedAtMillis; }

    private static Map<MediaApp, State> immutable(Map<MediaApp, State> source) {
        EnumMap<MediaApp, State> copy = new EnumMap<>(MediaApp.class);
        if (source != null) copy.putAll(source);
        return Collections.unmodifiableMap(copy);
    }
}
