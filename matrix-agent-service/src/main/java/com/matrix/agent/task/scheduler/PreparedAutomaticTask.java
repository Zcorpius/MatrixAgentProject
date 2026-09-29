package com.matrix.agent.task.scheduler;

import com.matrix.agent.identity.*;
import java.util.UUID;

/** Captured identity and stable request ID; unrelated to conversation submission or steering. */
public record PreparedAutomaticTask(String runtimeRequestId, String runId, String text, Actor actor,
        VehicleZone zone, long dataEpoch, long timeoutMillis, boolean readOnly, ExecutionScope scope) {
    public static final int MAX_TEXT_CHARS = 4096;
    public static final int MAX_TEXT_UTF8_BYTES = 8192;

    public PreparedAutomaticTask {
        if (!UUID.fromString(runtimeRequestId).toString().equals(runtimeRequestId)
                || !UUID.fromString(runId).toString().equals(runId)) throw new IllegalArgumentException("invalid execution identity");
        if (actor == null || zone == null || text == null || text.isBlank() || text.length() > MAX_TEXT_CHARS
                || text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_TEXT_UTF8_BYTES
                || timeoutMillis <= 0 || scope == null || !scope.automatic()
                || timeoutMillis > scope.profile().maxTaskMillis()) {
            throw new IllegalArgumentException("invalid automatic execution specification");
        }
        if ((actor == Actor.DRIVER && zone != VehicleZone.DRIVER)
                || (actor == Actor.PASSENGER && zone != VehicleZone.PASSENGER)) throw new IllegalArgumentException("actor/zone mismatch");
    }
}
