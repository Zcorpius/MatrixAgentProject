package com.matrix.agent.platform.media;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class MediaSwitchIntentTest {
    @Test public void recognizesImplicitMediaTargetAndExplicitTwoAppHandoff() {
        assertTrue(MediaSwitchIntent.isRequested("切换音乐来源"));
        assertTrue(MediaSwitchIntent.isRequested("切换到QQ音乐"));
        assertTrue(MediaSwitchIntent.isRequested("暂停QQ音乐再播放B站"));
        assertTrue(MediaSwitchIntent.isRequested("暂停 QQ 音乐再播放 B 站"));
        assertEquals(MediaApp.BILIBILI,
                ExplicitMediaTarget.switchTarget("暂停 QQ 音乐再播放 B 站"));
        assertEquals(MediaApp.QQMUSIC,
                ExplicitMediaTarget.switchTarget("先暂停 B 站，然后播放 QQ 音乐"));
    }

    @Test public void negatedCueAndComparisonAreNotSwitchRequests() {
        assertFalse(MediaSwitchIntent.isRequested("别切换，先暂停QQ音乐"));
        assertFalse(MediaSwitchIntent.isRequested("不要切换到QQ音乐"));
        assertFalse(MediaSwitchIntent.isRequested("不想切换音乐来源"));
        assertFalse(MediaSwitchIntent.isRequested("别再切换到QQ音乐"));
        assertFalse(MediaSwitchIntent.isRequested("比较QQ音乐和B站"));
        assertFalse(MediaSwitchIntent.isRequested("B 站在播放，QQ音乐暂停了"));
        assertNull(ExplicitMediaTarget.switchTarget("B 站在播放，QQ音乐暂停了"));
        assertFalse(MediaSwitchIntent.isRequested("暂停 QQ 音乐再播放 QQ 音乐"));
    }
}
