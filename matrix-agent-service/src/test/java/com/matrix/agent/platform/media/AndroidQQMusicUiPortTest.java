package com.matrix.agent.platform.media;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public final class AndroidQQMusicUiPortTest {
    @Test public void combinedArtistAndTitleMatchAcrossResultFields() {
        List<QQMusicUiPort.Candidate> results = List.of(
                new QQMusicUiPort.Candidate(1, "传奇", "李健·似水流年"));
        assertTrue(AndroidQQMusicUiPort.queryRepresented("李健 传奇", results));
        assertTrue(AndroidQQMusicUiPort.queryRepresented("  李健   传奇  ", results));
    }

    @Test public void unrelatedResultsCannotSatisfyCombinedQuery() {
        List<QQMusicUiPort.Candidate> results = List.of(
                new QQMusicUiPort.Candidate(1, "传奇", "王菲·传奇"),
                new QQMusicUiPort.Candidate(2, "贝加尔湖畔", "李健·依然"));
        assertFalse(AndroidQQMusicUiPort.queryRepresented("李健 传奇", results));
    }
}
