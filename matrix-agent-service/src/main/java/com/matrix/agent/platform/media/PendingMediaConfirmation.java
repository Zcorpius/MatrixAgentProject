package com.matrix.agent.platform.media;

import java.util.List;
import java.util.Optional;

/** Exposes only whether a session may answer a previously proposed QQ Music selection. */
@FunctionalInterface
public interface PendingMediaConfirmation {
    boolean hasPendingConfirmation(String sessionId);

    record Snapshot(List<QQMusicUiPort.Candidate> candidates, int confirmableIndex,
            String originalRequestText) {
        public Snapshot { candidates = List.copyOf(candidates); }
    }

    default Optional<Snapshot> snapshot(String sessionId) { return Optional.empty(); }

    default void discard(String sessionId) {}
}
