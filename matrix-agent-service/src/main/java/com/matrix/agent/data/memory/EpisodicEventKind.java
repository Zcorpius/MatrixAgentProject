package com.matrix.agent.data.memory;

/**
 * Versioned episodic action vocabulary. Version 2 adds bounded verified facts for supported
 * capabilities. RoomMemoryWriter keeps at most 100 events per owner/zone for 30 days.
 */
public enum EpisodicEventKind {
    CLIMATE("climate", "vehicle.climate."),
    NAVIGATION("navigation", "navigation."),
    MEDIA("media", "system.media."),
    DISPLAY("display", "system.display."),
    SEAT("seat", "vehicle.seat.");

    public static final int SCHEMA_VERSION = 2;

    private final String wireValue;
    private final String capabilityPrefix;

    EpisodicEventKind(String wireValue, String capabilityPrefix) {
        this.wireValue = wireValue;
        this.capabilityPrefix = capabilityPrefix;
    }

    public String wireValue() { return wireValue; }

    public static EpisodicEventKind fromCapability(String capability) {
        if (capability == null || capability.length() > 64) return null;
        if (capability.startsWith("climate.")) return CLIMATE; // older safe capability alias
        if (capability.startsWith("media.")) return MEDIA;
        for (EpisodicEventKind kind : values()) {
            if (capability.startsWith(kind.capabilityPrefix)) return kind;
        }
        return null;
    }

    public static EpisodicEventKind fromWireValue(String value) {
        for (EpisodicEventKind kind : values()) {
            if (kind.wireValue.equals(value)) return kind;
        }
        return null;
    }
}
