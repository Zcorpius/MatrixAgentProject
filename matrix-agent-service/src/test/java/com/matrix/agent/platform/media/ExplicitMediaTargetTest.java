package com.matrix.agent.platform.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ExplicitMediaTargetTest {
    @Test public void aliasesAreSharedBySingleAndDirectionalTargetDetection() {
        assertEquals(MediaApp.QQMUSIC,
                ExplicitMediaTarget.singleTarget("播放扣扣音乐"));
        assertEquals(MediaApp.QQMUSIC,
                ExplicitMediaTarget.switchTarget("切换到扣扣音乐，从B站过来"));
        assertEquals(MediaApp.BILIBILI,
                ExplicitMediaTarget.switchTarget("改用bilibili，从QQ音乐过来"));
        assertTrue(ExplicitMediaTarget.mentions("切换到扣扣音乐", MediaApp.QQMUSIC));
        assertNull(ExplicitMediaTarget.singleTarget("切换到QQ音乐，从B站过来"));
    }
}
