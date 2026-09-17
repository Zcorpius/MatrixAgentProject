package com.matrix.agent.task.capability;

import java.util.Map;

@FunctionalInterface
public interface CapabilityValidator {
    /** Returns null when valid, otherwise a user-readable rejection reason. */
    String validate(Map<String, Object> arguments);
}
