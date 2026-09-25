package com.matrix.agent.platform.media;

import java.util.List;

/** Package-bound UI access for Bilibili title search and a later explicit result choice. */
public interface BilibiliUiPort {
    record Candidate(int index, String title, String kind, String detail) {}
    record SearchPage(String query, long appVersion, List<Candidate> candidates) {}

    SearchPage search(String query, long deadlineElapsedMillis) throws MediaPlatformException;

    /** Rechecks the live result row before dispatching one user-selected click. */
    void open(SearchPage page, Candidate candidate, long deadlineElapsedMillis)
            throws MediaPlatformException;
}
