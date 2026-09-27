package com.matrix.agent.platform.media;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class BilibiliResultRulesTest {
    @Test public void candidateMustBeFullyInsideVisibleResultBounds() {
        BilibiliResultRules.Bounds results = new BilibiliResultRules.Bounds(0, 330, 1080, 1350);
        assertTrue(BilibiliResultRules.within(
                new BilibiliResultRules.Bounds(33, 460, 356, 1003), results));
        assertFalse(BilibiliResultRules.within(
                new BilibiliResultRules.Bounds(0, 100, 1080, 200), results));
        assertFalse(BilibiliResultRules.within(
                new BilibiliResultRules.Bounds(100, 500, 100, 600), results));
    }

    @Test public void staleRowWithSameTitleButDifferentDetailIsRejected() {
        BilibiliUiPort.Candidate original = new BilibiliUiPort.Candidate(
                1, "逃避虽可耻但有用", "番剧/影视", "相关作品");
        BilibiliUiPort.Candidate same = new BilibiliUiPort.Candidate(
                2, "逃避虽可耻但有用", "番剧/影视", "相关作品");
        BilibiliUiPort.Candidate changed = new BilibiliUiPort.Candidate(
                1, "逃避虽可耻但有用", "视频", "UP 主：其他人");
        assertTrue(BilibiliResultRules.sameResult(original, same));
        assertFalse(BilibiliResultRules.sameResult(original, changed));
    }
}
