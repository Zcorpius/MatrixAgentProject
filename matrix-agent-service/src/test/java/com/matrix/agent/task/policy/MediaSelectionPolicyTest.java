package com.matrix.agent.task.policy;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.task.capability.MediaCapabilities;

import org.junit.Test;

public final class MediaSelectionPolicyTest {
    private final MediaSelectionPolicy policy = new MediaSelectionPolicy();

    @Test public void specifiedArtistCannotResumeAnUnrelatedQueue() {
        assertNotNull(policy.evaluate(
                AgentRequest.builder("打开QQ音乐，播放李健的歌", Actor.DRIVER).build(),
                MediaCapabilities.QQ_PLAY));
        assertNotNull(policy.evaluate(
                AgentRequest.builder("Open QQ Music and play songs by Li Jian", Actor.DRIVER)
                        .build(), MediaCapabilities.QQ_PLAY));
    }

    @Test public void genericResumeRemainsAvailable() {
        assertNull(policy.evaluate(
                AgentRequest.builder("继续播放 QQ 音乐", Actor.DRIVER).build(),
                MediaCapabilities.QQ_PLAY));
        assertNull(policy.evaluate(
                AgentRequest.builder("播放 QQ 音乐", Actor.DRIVER).build(),
                MediaCapabilities.QQ_PLAY));
        assertNull(policy.evaluate(
                AgentRequest.builder("继续播放哔哩哔哩", Actor.DRIVER).build(),
                MediaCapabilities.BILI_RESUME));
    }

    @Test public void bilibiliTitleCannotResumeAnUnrelatedVideo() {
        assertNotNull(policy.evaluate(
                AgentRequest.builder("播放哔哩哔哩的逃避可耻但是有用", Actor.DRIVER).build(),
                MediaCapabilities.BILI_RESUME));
    }
}
