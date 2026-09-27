package com.matrix.agent.platform.media;

/** In-memory availability hint; never grants permission to execute a Tool. */
@FunctionalInterface
public interface MediaAvailabilitySource {
    MediaAvailabilitySnapshot peekAndWarm();
}
