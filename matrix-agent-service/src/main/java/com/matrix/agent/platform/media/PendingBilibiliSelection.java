package com.matrix.agent.platform.media;

import java.util.List;
import java.util.Optional;

/** Session-scoped search candidates awaiting a new user utterance that identifies one result. */
public interface PendingBilibiliSelection {
    record Snapshot(List<BilibiliUiPort.Candidate> candidates, String originalRequestText) {
        public Snapshot { candidates = List.copyOf(candidates); }
    }

    Optional<Snapshot> bilibiliSnapshot(String sessionId);
    void discardBilibili(String sessionId);
}
