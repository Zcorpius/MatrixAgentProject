package com.matrix.agent.platform.media;

import java.util.List;

/** Narrow, package-bound UI boundary for QQ Music search and explicit result selection. */
public interface QQMusicUiPort {
    record Candidate(int index, String title, String detail) {}
    record SearchPage(String query, long appVersion, List<Candidate> candidates) {}

    SearchPage search(String query, long deadlineElapsedMillis) throws MediaPlatformException;

    /** Clicks only a row whose title and detail still match the previously shown candidate. */
    void select(SearchPage page, Candidate candidate, long deadlineElapsedMillis)
            throws MediaPlatformException;
}
