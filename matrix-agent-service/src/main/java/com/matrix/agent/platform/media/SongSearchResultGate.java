package com.matrix.agent.platform.media;

import java.util.List;

/** Page freshness only. Search relevance and authorization are separate from UI readiness. */
final class SongSearchResultGate {
    private static final long SETTLE_MILLIS = 240L;
    private final String query;
    private final String previousQuery;
    private final List<QQMusicUiPort.Candidate> previousCandidates;
    private List<QQMusicUiPort.Candidate> lastCandidates = List.of();
    private long stableSince;

    SongSearchResultGate(String query, String previousQuery,
            List<QQMusicUiPort.Candidate> previousCandidates) {
        this.query = query;
        this.previousQuery = previousQuery;
        this.previousCandidates = List.copyOf(previousCandidates);
    }

    boolean accept(String displayedQuery, List<QQMusicUiPort.Candidate> candidates, long now) {
        if (!query.equals(displayedQuery) || candidates.isEmpty()
                || (!query.equals(previousQuery) && candidates.equals(previousCandidates))) {
            lastCandidates = List.of();
            return false;
        }
        if (!candidates.equals(lastCandidates)) {
            lastCandidates = List.copyOf(candidates);
            stableSince = now;
            return false;
        }
        return now - stableSince >= SETTLE_MILLIS;
    }
}
