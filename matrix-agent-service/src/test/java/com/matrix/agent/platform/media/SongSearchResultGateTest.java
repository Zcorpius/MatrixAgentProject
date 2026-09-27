package com.matrix.agent.platform.media;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import java.util.List;

public final class SongSearchResultGateTest {
    private final List<QQMusicUiPort.Candidate> results = List.of(
            new QQMusicUiPort.Candidate(1, "消失的月光", "李健·李健"));

    @Test public void correctedResultsCanBecomeReadyWithoutLiteralQueryTokens() {
        var gate = new SongSearchResultGate("李健的消失 月光", "旧查询", List.of());
        assertFalse(gate.accept("李健的消失 月光", results, 0));
        assertFalse(gate.accept("李健的消失 月光", results, 120));
        assertTrue(gate.accept("李健的消失 月光", results, 240));
    }

    @Test public void newInputWithOldResultsNeverBecomesReady() {
        var gate = new SongSearchResultGate("新查询", "旧查询", results);
        assertFalse(gate.accept("新查询", results, 0));
        assertFalse(gate.accept("新查询", results, 10_000));
    }

    @Test public void unchangedQueryCanReuseItsStableRows() {
        var gate = new SongSearchResultGate("月光", "月光", results);
        assertFalse(gate.accept("月光", results, 100));
        assertTrue(gate.accept("月光", results, 340));
    }

    @Test public void wrongInputEmptyResultsAndChangingRowsResetStability() {
        var gate = new SongSearchResultGate("月光", "旧查询", List.of());
        assertFalse(gate.accept("旧查询", results, 0));
        assertFalse(gate.accept("月光", results, 100));
        assertFalse(gate.accept("月光", List.of(), 340));
        assertFalse(gate.accept("月光", results, 500));
        var changed = List.of(new QQMusicUiPort.Candidate(2, "月光 (Live)", "其他歌手"));
        assertFalse(gate.accept("月光", changed, 700));
        assertFalse(gate.accept("月光", changed, 800));
        assertTrue(gate.accept("月光", changed, 940));
    }
}
