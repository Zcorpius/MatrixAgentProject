package com.matrix.agent.contract;

public enum PlannerMode {
    NATIVE_TOOL_CALLING("Native Tool Calling"),
    STRUCTURED_JSON_COMPATIBILITY("Structured JSON Compatibility");

    public final String displayName;

    PlannerMode(String displayName) {
        this.displayName = displayName;
    }
}
