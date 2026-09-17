package com.matrix.agent.task.identity;

import java.util.Locale;

public enum VehicleZone {
    DRIVER("driver"),
    PASSENGER("passenger"),
    GLOBAL("global");

    private final String wireValue;

    VehicleZone(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static VehicleZone parse(Object value) {
        if (value instanceof VehicleZone) return (VehicleZone) value;
        if (!(value instanceof String)) return null;
        String normalized = ((String) value).trim().toLowerCase(Locale.ROOT);
        for (VehicleZone zone : values()) {
            if (zone.wireValue.equals(normalized) || zone.name().toLowerCase(Locale.ROOT).equals(normalized)) {
                return zone;
            }
        }
        return null;
    }
}
