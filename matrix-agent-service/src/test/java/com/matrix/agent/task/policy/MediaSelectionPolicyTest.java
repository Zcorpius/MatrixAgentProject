package com.matrix.agent.task.policy;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.task.capability.MediaCapabilities;

import org.junit.Test;

public final class MediaSelectionPolicyTest {
    private final MediaSelectionPolicy policy = new MediaSelectionPolicy();

    @Test public void baselineUnexpectedEffectsAreBlockedBeforeProvider() {
        assertNotNull(policy.evaluate(AgentRequest.builder("继续播放QQ音乐但当前没有播放会话", Actor.DRIVER).build(), MediaCapabilities.QQ_OPEN));
        assertNotNull(policy.evaluate(AgentRequest.builder("在B站打开BV1xx411c7mD的第2P", Actor.DRIVER).build(), MediaCapabilities.BILI_RESUME));
        for (String instruction : java.util.List.of("播放那个", "换一个平台播放", "play that", "switch platform")) {
            for (String capability : java.util.List.of(MediaCapabilities.QQ_PLAY, MediaCapabilities.BILI_RESUME)) {
                assertNotNull(policy.evaluate(AgentRequest.builder(instruction, Actor.DRIVER).build(), capability));
            }
        }
        assertNull(policy.evaluate(AgentRequest.builder("打开QQ音乐", Actor.DRIVER).build(), MediaCapabilities.QQ_OPEN));
        assertNull(policy.evaluate(AgentRequest.builder("在B站播放BV1xx411c7mD", Actor.DRIVER).build(), MediaCapabilities.BILI_RESUME));
        assertNull(policy.evaluate(AgentRequest.builder("换到QQ音乐播放", Actor.DRIVER).build(), MediaCapabilities.QQ_PLAY));
    }

    @Test public void explicitSearchOrConfirmationCannotBeBypassedByResume() {
        for (String text : java.util.List.of("QQ音乐请播放林舟的晨光，先确认选曲", "QQ音乐先搜索晨光")) {
            assertNotNull(policy.evaluate(AgentRequest.builder(text, Actor.DRIVER).build(), MediaCapabilities.QQ_PLAY));
        }
        assertNotNull(policy.evaluate(AgentRequest.builder("B站请播放星海旅行，先搜索标题", Actor.DRIVER).build(),
                MediaCapabilities.BILI_RESUME));
        assertNotNull(policy.evaluate(AgentRequest.builder("改用B站搜索星际穿越", Actor.DRIVER).build(),
                MediaCapabilities.BILI_RESUME));
        assertNull(policy.evaluate(AgentRequest.builder("继续播放QQ音乐，不要搜索", Actor.DRIVER).build(),
                MediaCapabilities.QQ_PLAY));
        assertNull(policy.evaluate(AgentRequest.builder("QQ音乐先搜索晨光", Actor.DRIVER).build(),
                MediaCapabilities.QQ_SEARCH));
        assertNull(policy.evaluate(AgentRequest.builder("先搜索B站，再继续QQ音乐", Actor.DRIVER).build(),
                MediaCapabilities.QQ_PLAY));
    }

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
