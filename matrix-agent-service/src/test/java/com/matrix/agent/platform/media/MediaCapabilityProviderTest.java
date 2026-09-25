package com.matrix.agent.platform.media;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.media.session.PlaybackState;

import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.task.capability.MediaCapabilities;
import com.matrix.agent.task.tool.ToolResult;

import org.junit.Test;

import java.util.Collections;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MediaCapabilityProviderTest {
    @Test public void missingAppIsProviderFailureWithStructuredCode() {
        MediaCapabilityProvider provider = new MediaCapabilityProvider(app -> false,
                app -> { throw new AssertionError("session must not be queried"); },
                new FakeLaunch());
        ToolResult result = provider.execute(request(), call(MediaCapabilities.QQ_STATE));
        assertEquals(ToolResult.Status.EXECUTION_FAILED, result.getStatus());
        assertEquals("APP_NOT_INSTALLED", result.getObservedState().get("media.error_code"));
        assertEquals(false, result.getObservedState().get("media.installed"));
    }

    @Test public void playRequiresObservedPlayingState() {
        FakeSession session = new FakeSession(
                state("PAUSED", PlaybackState.ACTION_PLAY, "one"),
                state("PLAYING", PlaybackState.ACTION_PLAY, "one"));
        MediaCapabilityProvider provider = provider(session);
        ToolResult result = provider.execute(request(), call(MediaCapabilities.QQ_PLAY));
        assertEquals(ToolResult.Status.SUCCESS, result.getStatus());
        assertTrue(result.isVerified());
        assertEquals("PLAYING", result.getObservedState().get("media.playback_state"));
        assertEquals(1, session.sends);
    }

    @Test public void nextWithoutComparableMetadataIsVerificationFailureAndSentOnce() {
        FakeSession session = new FakeSession(
                state("PLAYING", PlaybackState.ACTION_SKIP_TO_NEXT, null),
                state("PLAYING", PlaybackState.ACTION_SKIP_TO_NEXT, null));
        MediaCapabilityProvider provider = provider(session);
        AgentRequest shortRequest = AgentRequest.builder("下一首", Actor.DRIVER)
                .timeoutMillis(650).build();
        ToolResult result = provider.execute(shortRequest, call(MediaCapabilities.QQ_NEXT));
        assertEquals(ToolResult.Status.VERIFICATION_FAILED, result.getStatus());
        assertFalse(result.isVerified());
        assertEquals(1, session.sends);
    }

    @Test public void openVideoValidatesEvenWhenPolicyWasBypassed() {
        FakeLaunch launch = new FakeLaunch();
        MediaCapabilityProvider provider = new MediaCapabilityProvider(app -> true,
                app -> { throw new AssertionError("session must not be queried"); }, launch);
        ToolResult invalid = provider.execute(request(), new ToolCall(MediaCapabilities.BILI_OPEN,
                Map.of("bvid", "invalid")));
        assertEquals(ToolResult.Status.EXECUTION_FAILED, invalid.getStatus());
        assertEquals("NOT_SENT", invalid.getObservedState().get("media.dispatch_state"));
        assertEquals(0, launch.opens);

        ToolResult valid = provider.execute(request(), new ToolCall(MediaCapabilities.BILI_OPEN,
                Map.of("bvid", "BV1xx411c7mD")));
        assertEquals(ToolResult.Status.SUCCESS, valid.getStatus());
        assertEquals("REQUESTED", valid.getObservedState().get("media.dispatch_state"));
        assertFalse(valid.isVerified());
        assertEquals(1, launch.opens);
    }

    @Test public void searchListsCandidatesAndRequiresAnotherExplicitUserTurnToPlay() {
        FakeUi ui = new FakeUi();
        MediaSessionPort.Session session = new MediaSessionPort.Session() {
            @Override public MediaSessionPort.Snapshot snapshot() {
                return new MediaSessionPort.Snapshot(ui.selected ? "PLAYING" : "PAUSED",
                        PlaybackState.ACTION_PLAY, ui.selected ? "贝加尔湖畔" : "明天过后",
                        ui.selected ? "李健" : "张杰", "id", -1L, 0L, 100_000L);
            }
            @Override public void send(MediaSessionPort.Action action, long positionMs) {
                throw new AssertionError("selection must use the exact UI row");
            }
        };
        MediaCapabilityProvider provider = new MediaCapabilityProvider(app -> true,
                app -> session, new FakeLaunch(), ui);
        AgentRequest searchRequest = AgentRequest.builder("搜索李健的歌", Actor.DRIVER)
                .sessionId("music-conversation").build();
        ToolResult search = provider.execute(searchRequest,
                new ToolCall(MediaCapabilities.QQ_SEARCH, Map.of("query", "李健")));
        assertEquals(ToolResult.Status.SUCCESS, search.getStatus());
        assertEquals(2, ((List<?>) search.getObservedState().get("media.candidates")).size());
        assertFalse(ui.selected);

        ToolCall select = new ToolCall(MediaCapabilities.QQ_PLAY_RESULT, Map.of("index", 2));
        ToolResult premature = provider.execute(searchRequest, select);
        assertEquals("SELECTION_NOT_CONFIRMED",
                premature.getObservedState().get("media.error_code"));
        assertFalse(ui.selected);

        AgentRequest chosen = AgentRequest.builder("播放第二首", Actor.DRIVER)
                .sessionId("music-conversation").build();
        ToolResult played = provider.execute(chosen, select);
        assertEquals(ToolResult.Status.SUCCESS, played.getStatus());
        assertTrue(played.isVerified());
        assertTrue(ui.selected);
        assertEquals("贝加尔湖畔", played.getObservedState().get("media.title"));
    }

    @Test public void affirmativeReplyCanPlayOnlyUniquelyProposedCandidate() {
        AtomicBoolean selected = new AtomicBoolean();
        QQMusicUiPort ui = new QQMusicUiPort() {
            @Override public SearchPage search(String query, long deadline) {
                return new SearchPage(query, 1L, List.of(
                        new Candidate(1, "传奇", "李健·似水流年"),
                        new Candidate(2, "传奇", "王菲·传奇")));
            }

            @Override public void select(SearchPage page, Candidate candidate, long deadline) {
                assertEquals(1, candidate.index());
                selected.set(true);
            }
        };
        MediaSessionPort.Session session = new MediaSessionPort.Session() {
            @Override public MediaSessionPort.Snapshot snapshot() {
                return new MediaSessionPort.Snapshot(selected.get() ? "PLAYING" : "PAUSED",
                        PlaybackState.ACTION_PLAY, selected.get() ? "传奇" : "明天过后",
                        selected.get() ? "李健" : "张杰", "id", -1L, 0L, 100_000L);
            }
            @Override public void send(MediaSessionPort.Action action, long positionMs) {
                throw new AssertionError("selection must use the UI row");
            }
        };
        MediaCapabilityProvider provider = new MediaCapabilityProvider(app -> true,
                app -> session, new FakeLaunch(), ui);
        AgentRequest searchRequest = AgentRequest.builder("播放李健的传奇", Actor.DRIVER)
                .sessionId("confirmation-conversation").build();
        ToolResult search = provider.execute(searchRequest,
                new ToolCall(MediaCapabilities.QQ_SEARCH, Map.of("query", "李健 传奇")));
        assertEquals(ToolResult.Status.SUCCESS, search.getStatus());
        assertEquals(1, search.getObservedState().get("media.confirmable_index"));
        assertTrue(provider.hasPendingConfirmation("confirmation-conversation"));
        assertFalse(selected.get());

        AgentRequest repeated = AgentRequest.builder("播放李健的传奇", Actor.DRIVER)
                .sessionId("confirmation-conversation").build();
        ToolResult notConfirmed = provider.execute(repeated,
                new ToolCall(MediaCapabilities.QQ_PLAY_RESULT, Map.of("index", 1)));
        assertEquals("SELECTION_NOT_CONFIRMED",
                notConfirmed.getObservedState().get("media.error_code"));
        assertFalse(selected.get());

        AgentRequest yes = AgentRequest.builder("是，播放吧", Actor.DRIVER)
                .sessionId("confirmation-conversation").build();
        ToolResult wrongCapability = provider.execute(yes, call(MediaCapabilities.QQ_PLAY));
        assertEquals("SELECTION_REQUIRES_RESULT",
                wrongCapability.getObservedState().get("media.error_code"));
        assertFalse(selected.get());
        ToolResult wrong = provider.execute(yes,
                new ToolCall(MediaCapabilities.QQ_PLAY_RESULT, Map.of("index", 2)));
        assertEquals("SELECTION_NOT_CONFIRMED",
                wrong.getObservedState().get("media.error_code"));
        assertFalse(selected.get());

        AgentRequest titleOnly = AgentRequest.builder("播放传奇", Actor.DRIVER)
                .sessionId("confirmation-conversation").build();
        ToolResult ambiguousTitle = provider.execute(titleOnly,
                new ToolCall(MediaCapabilities.QQ_PLAY_RESULT, Map.of("index", 2)));
        assertEquals("SELECTION_NOT_CONFIRMED",
                ambiguousTitle.getObservedState().get("media.error_code"));
        assertFalse(selected.get());

        ToolResult played = provider.execute(yes,
                new ToolCall(MediaCapabilities.QQ_PLAY_RESULT, Map.of("index", 1)));
        assertEquals(ToolResult.Status.SUCCESS, played.getStatus());
        assertTrue(played.isVerified());
        assertTrue(selected.get());
        assertFalse(provider.hasPendingConfirmation("confirmation-conversation"));
    }

    @Test public void negatedTitleMentionCannotAuthorizeSelection() {
        FakeUi ui = new FakeUi();
        MediaCapabilityProvider provider = new MediaCapabilityProvider(app -> true,
                app -> { throw new AssertionError("session must not be queried"); },
                new FakeLaunch(), ui);
        AgentRequest search = AgentRequest.builder("搜索李健的歌", Actor.DRIVER)
                .sessionId("negative-choice").build();
        provider.execute(search, new ToolCall(MediaCapabilities.QQ_SEARCH,
                Map.of("query", "李健")));
        AgentRequest negative = AgentRequest.builder("不要播放贝加尔湖畔，换个别的", Actor.DRIVER)
                .sessionId("negative-choice").build();
        ToolResult rejected = provider.execute(negative,
                new ToolCall(MediaCapabilities.QQ_PLAY_RESULT, Map.of("index", 2)));
        assertEquals("SELECTION_NOT_CONFIRMED",
                rejected.getObservedState().get("media.error_code"));
        assertFalse(ui.selected);
    }

    private static MediaCapabilityProvider provider(FakeSession session) {
        return new MediaCapabilityProvider(app -> true, app -> session, new FakeLaunch());
    }

    private static AgentRequest request() {
        return AgentRequest.builder("媒体操作", Actor.DRIVER).build();
    }

    private static ToolCall call(String capability) {
        return new ToolCall(capability, Collections.emptyMap());
    }

    private static MediaSessionPort.Snapshot state(String playback, long actions, String id) {
        return new MediaSessionPort.Snapshot(playback, actions, null, id, -1L, 0L, 10_000L);
    }

    private static final class FakeSession implements MediaSessionPort.Session {
        private final MediaSessionPort.Snapshot before;
        private final MediaSessionPort.Snapshot after;
        int sends;

        FakeSession(MediaSessionPort.Snapshot before, MediaSessionPort.Snapshot after) {
            this.before = before;
            this.after = after;
        }

        @Override public MediaSessionPort.Snapshot snapshot() {
            return sends == 0 ? before : after;
        }

        @Override public void send(MediaSessionPort.Action action, long positionMs) {
            sends++;
        }
    }

    private static final class FakeLaunch implements AppLaunchPort {
        int opens;
        @Override public void openApp(MediaApp app) { opens++; }
        @Override public void openBilibiliVideo(String bvid, Integer page) { opens++; }
    }

    private static final class FakeUi implements QQMusicUiPort {
        boolean selected;

        @Override public SearchPage search(String query, long deadline) {
            return new SearchPage(query, 1L, List.of(
                    new Candidate(1, "人间共鸣", "李健·人间共鸣"),
                    new Candidate(2, "贝加尔湖畔", "李健·依然")));
        }

        @Override public void select(SearchPage page, Candidate candidate, long deadline) {
            assertEquals("贝加尔湖畔", candidate.title());
            selected = true;
        }
    }
}
