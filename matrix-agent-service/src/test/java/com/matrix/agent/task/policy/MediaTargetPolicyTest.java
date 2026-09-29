package com.matrix.agent.task.policy;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.task.capability.MediaCapabilities;

import org.junit.Test;

public final class MediaTargetPolicyTest {
    private final MediaTargetPolicy policy = new MediaTargetPolicy();

    @Test public void bilibiliTitleCannotOpenOrSearchQQMusic() {
        AgentRequest request = AgentRequest.builder(
                "播放哔哩哔哩的逃避可耻但是有用", Actor.DRIVER).build();
        assertNotNull(policy.evaluate(request, MediaCapabilities.QQ_SEARCH));
        assertNotNull(policy.evaluate(request, MediaCapabilities.QQ_OPEN));
        assertNotNull(policy.evaluate(request, MediaCapabilities.QQ_PLAY_RESULT));
        assertNull(policy.evaluate(request, MediaCapabilities.BILI_OPEN));
    }

    @Test public void dualAppHandoffMayControlBothSources() {
        AgentRequest request = AgentRequest.builder(
                "暂停 QQ 音乐，切换到哔哩哔哩", Actor.DRIVER).build();
        assertNull(policy.evaluate(request, MediaCapabilities.QQ_PAUSE));
        assertNull(policy.evaluate(request, MediaCapabilities.BILI_OPEN));
    }

    @Test public void unnamedArtistSongRequestMayUseQQMusic() {
        AgentRequest request = AgentRequest.builder("播放周深的歌", Actor.DRIVER).build();
        assertNull(policy.evaluate(request, MediaCapabilities.QQ_SEARCH));
    }

    @Test public void switchTargetAllowsSourcePauseButOrdinaryRequestDoesNot() {
        AgentRequest switchRequest = AgentRequest.builder("切换到QQ音乐", Actor.DRIVER).build();
        AgentRequest ordinary = AgentRequest.builder("播放QQ音乐", Actor.DRIVER).build();
        assertNull(policy.evaluate(switchRequest, MediaCapabilities.BILI_PAUSE));
        assertNotNull(policy.evaluate(ordinary, MediaCapabilities.BILI_PAUSE));
        AgentRequest negated = AgentRequest.builder("别切换，先暂停QQ音乐", Actor.DRIVER).build();
        assertNull(policy.evaluate(negated, MediaCapabilities.QQ_PAUSE));
        assertNotNull(policy.evaluate(negated, MediaCapabilities.BILI_PAUSE));
    }
}
