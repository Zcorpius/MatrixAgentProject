package com.matrix.agent.task.skill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.identity.RuntimeProfile;
import com.matrix.agent.platform.media.BilibiliUiPort;
import com.matrix.agent.platform.media.MediaAvailabilitySnapshot;
import com.matrix.agent.platform.media.PendingBilibiliSelection;
import com.matrix.agent.task.capability.CapabilityRegistry;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

public final class SkillSelectorTest {
    @Test public void picksSwitchSkillForSingleNamedTargetWithoutBinderRead() {
        AtomicInteger hintReads = new AtomicInteger();
        SkillSelector selector = selector(hintReads, noBilibiliPending());
        String prompt = selector.promptFor(request("切换到QQ音乐"));
        assertTrue(prompt.contains("id=\"media-source-switch\""));
        assertEquals(1, hintReads.get());
    }

    @Test public void namelessSwitchGetsGuardedSkillButNegatedSwitchGetsQqControl() {
        SkillSelector selector = selector(new AtomicInteger(), noBilibiliPending());
        assertTrue(selector.promptFor(request("切换音乐来源"))
                .contains("id=\"media-source-switch\""));
        String pause = selector.promptFor(request("别切换，先暂停QQ音乐"));
        assertTrue(pause.contains("id=\"qqmusic-control\""));
    }

    @Test public void picksBilibiliSkillForTitleAndPendingIndexReply() {
        AtomicInteger hintReads = new AtomicInteger();
        PendingBilibiliSelection pending = new PendingBilibiliSelection() {
            @Override public Optional<Snapshot> bilibiliSnapshot(String sessionId) {
                return Optional.of(new Snapshot(List.of(new BilibiliUiPort.Candidate(
                        2, "逃避虽可耻但有用", "番剧/影视", "相关作品")), "原请求"));
            }
            @Override public void discardBilibili(String sessionId) {}
        };
        SkillSelector selector = selector(hintReads, pending);
        assertTrue(selector.promptFor(request("播放哔哩哔哩的逃避可耻但是有用"))
                .contains("id=\"bilibili-open-video\""));
        assertTrue(selector.promptFor(request("第2个"))
                .contains("id=\"bilibili-open-video\""));
        assertEquals(2, hintReads.get());
    }

    private static AgentRequest request(String text) {
        return AgentRequest.builder(text, Actor.DRIVER).sessionId("media-session")
                .runtimeProfile(RuntimeProfile.PHONE).build();
    }

    private static PendingBilibiliSelection noBilibiliPending() {
        return new PendingBilibiliSelection() {
            @Override public Optional<Snapshot> bilibiliSnapshot(String sessionId) {
                return Optional.empty();
            }
            @Override public void discardBilibili(String sessionId) {}
        };
    }

    private static SkillSelector selector(AtomicInteger hintReads,
            PendingBilibiliSelection pending) {
        SkillCatalog.Source source = new SkillCatalog.Source() {
            private final String[] ids = {"qqmusic-control", "bilibili-open-video",
                    "media-source-switch"};
            @Override public String[] list(String path) { return ids; }
            @Override public InputStream open(String path) {
                String id = path.split("/")[1];
                String content = path.endsWith("manifest.json")
                        ? "{\"id\":\"" + id + "\",\"version\":1,"
                        + "\"required_capabilities\":[],\"profiles\":[\"PHONE\"],"
                        + "\"instructions\":\"SKILL.md\"}"
                        : "INSTRUCTIONS:" + id;
                return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
            }
        };
        return new SkillSelector(new SkillCatalog(source,
                CapabilityRegistry.createRuntimeRegistry()), () -> {
                    hintReads.incrementAndGet();
                    return MediaAvailabilitySnapshot.unknown();
                }, sessionId -> false, pending);
    }
}
