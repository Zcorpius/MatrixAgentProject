package com.matrix.agent.evaluation;

import static com.matrix.agent.evaluation.EvaluationCase.Action;

import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.platform.media.*;
import com.matrix.agent.schedule.domain.ClockSample;
import com.matrix.agent.schedule.domain.ScheduleCodec;
import com.matrix.agent.schedule.domain.ScheduleIdentity;
import com.matrix.agent.schedule.store.ScheduleStore;
import com.matrix.agent.schedule.store.ScheduleStoreFixture;
import com.matrix.agent.schedule.tool.ScheduleCapabilityProvider;
import com.matrix.agent.task.capability.CapabilityProvider;
import com.matrix.agent.task.tool.ToolResult;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Isolated synthetic world. Production providers own authorization, selection, and normalization. */
final class FixtureWorld implements CapabilityProvider {
    private final Map<String, Object> fixture;
    private final Map<MediaApp, FakeSession> sessions = new EnumMap<>(MediaApp.class);
    private final List<Action> effects = new ArrayList<>();
    private final List<Action> observations = new ArrayList<>();
    private final ScheduleStoreFixture database = new ScheduleStoreFixture();
    private final ScheduleStore store = new ScheduleStore(database, () -> 1L, () -> false);
    private final ScheduleCapabilityProvider schedules;
    final MediaCapabilityProvider media;

    FixtureWorld(Map<String, Object> fixture) {
        this.fixture = fixture;
        for (MediaApp app : MediaApp.values()) sessions.put(app, new FakeSession(app));
        media = new MediaCapabilityProvider(this::installed,
                app -> {
                    if (!flag(app.wireName() + "Session", true)) throw new MediaPlatformException("NO_ACTIVE_SESSION");
                    return sessions.get(app);
                },
                new AppLaunchPort() {
                    @Override public void openApp(MediaApp app, LaunchContext context) {
                        effect(app.wireName() + ".open", Map.of());
                    }
                    @Override public void openBilibiliVideo(String bvid, Integer page, LaunchContext context) {
                        effect("bilibili.open_video", Map.of("bvid", bvid, "page", page == null ? 1 : page));
                    }
                }, new QQMusicUiPort() {
                    @Override public SearchPage search(String query, LaunchContext context) throws MediaPlatformException {
                        failSearch();
                        if (candidates("qqCandidates").isEmpty()) throw new MediaPlatformException("SEARCH_NO_RESULTS");
                        return new SearchPage(query, 1L, candidates("qqCandidates").stream()
                                .map(row -> new Candidate(number(row, "index", 1), text(row, "title", ""),
                                        text(row, "detail", ""))).toList());
                    }
                    @Override public void select(SearchPage page, Candidate candidate, LaunchContext context)
                            throws MediaPlatformException {
                        if (flag("staleRow", false)) throw new MediaPlatformException("SEARCH_RESULT_CHANGED");
                        FakeSession session = sessions.get(MediaApp.QQMUSIC);
                        session.title = candidate.title();
                        session.artist = candidate.detail().split("·", 2)[0].strip();
                        session.state = "PLAYING";
                        effect("qqmusic.select", Map.of("index", candidate.index(), "title", candidate.title(),
                                "artist", session.artist));
                    }
                }, new BilibiliUiPort() {
                    @Override public SearchPage search(String query, LaunchContext context) throws MediaPlatformException {
                        failSearch();
                        if (candidates("biliCandidates").isEmpty()) throw new MediaPlatformException("SEARCH_NO_RESULTS");
                        return new SearchPage(query, 1L, candidates("biliCandidates").stream()
                                .map(row -> new Candidate(number(row, "index", 1), text(row, "title", ""),
                                        text(row, "kind", "video"), text(row, "detail", ""))).toList());
                    }
                    @Override public void open(SearchPage page, Candidate candidate, LaunchContext context)
                            throws MediaPlatformException {
                        if (flag("staleRow", false)) throw new MediaPlatformException("SEARCH_RESULT_CHANGED");
                        effect("bilibili.select", Map.of("index", candidate.index(), "title", candidate.title()));
                    }
                }, ExternalAppHandoffPort.NONE);
        schedules = new ScheduleCapabilityProvider(new ScheduleCapabilityProvider.Backend() {
            @Override public ScheduleIdentity identity(AgentRequest request) {
                return new ScheduleIdentity(10001, 0, "evaluation.synthetic", "fixture-signature",
                        request.getActor(), request.getOccupantZone());
            }
            @Override public ClockSample clock() {
                return new ClockSample(Instant.parse(text(fixture, "now", "2026-09-27T00:00:00Z")),
                        10_000, "evaluation-boot", ZoneId.of(text(fixture, "zone", "Asia/Shanghai")));
            }
            @Override public <T> T execute(Function<ScheduleStore, T> action) { return action.apply(store); }
            @Override public void changed() { /* No Android effects are registered by this fixture. */ }
        });
    }

    void beginTurn(String sessionId, boolean expireCandidates) {
        effects.clear();
        observations.clear();
        if (expireCandidates) {
            media.discard(sessionId);
            media.discardBilibili(sessionId);
        }
    }

    @Override public ToolResult execute(AgentRequest request, ToolCall call) {
        Map<String, Long> before = new LinkedHashMap<>();
        database.definitions().forEach(row -> before.put(row.scheduleId, row.revision));
        ToolResult result = call.getCapabilityName().startsWith("media.") ? media.execute(request, call)
                : call.getCapabilityName().startsWith("schedule.") ? schedules.execute(request, call)
                : ToolResult.rejected(call.getCapabilityName(), "FIXTURE_CAPABILITY_UNAVAILABLE");
        // Observe committed state, never infer a write from SUCCESS or a proposed tool call.
        for (var row : database.definitions()) {
            if (before.getOrDefault(row.scheduleId, -1L) == row.revision) continue;
            var spec = ScheduleCodec.spec(row.specJson);
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("title", spec.title);
            state.put("text", spec.action.text);
            state.put("actionKind", spec.action.kind);
            state.put("capabilities", spec.action.capabilities);
            state.put("timeZone", spec.timing.zoneId);
            state.put("nextDueAt", row.nextDueAt == null ? 0L : row.nextDueAt);
            state.put("state", row.state);
            state.put("allowNetwork", spec.action.allowNetwork);
            state.put("speakResult", spec.action.speakResult);
            effect(before.containsKey(row.scheduleId) ? "schedule.control" : "schedule.create", state);
        }
        observations.add(new Action(call.getCapabilityName(), Map.of("arguments", call.getArguments(),
                "status", result.getStatus().name(), "state", result.getObservedState(),
                "verified", result.isVerified())));
        return result;
    }

    List<Action> effects() { return List.copyOf(effects); }
    List<Action> observations() { return List.copyOf(observations); }

    MediaAvailabilitySnapshot availability() {
        Map<MediaApp, MediaAvailabilitySnapshot.State> apps = new EnumMap<>(MediaApp.class);
        Map<MediaApp, MediaAvailabilitySnapshot.State> active = new EnumMap<>(MediaApp.class);
        for (MediaApp app : MediaApp.values()) {
            apps.put(app, installed(app) ? MediaAvailabilitySnapshot.State.AVAILABLE : MediaAvailabilitySnapshot.State.ABSENT);
            active.put(app, flag(app.wireName() + "Session", true)
                    ? MediaAvailabilitySnapshot.State.AVAILABLE : MediaAvailabilitySnapshot.State.ABSENT);
        }
        return new MediaAvailabilitySnapshot(apps, active, 0L);
    }

    private void effect(String kind, Map<String, Object> attributes) { effects.add(new Action(kind, Map.copyOf(attributes))); }
    private boolean installed(MediaApp app) { return flag(app.wireName() + "Installed", true); }
    private boolean flag(String key, boolean fallback) { return (Boolean) fixture.getOrDefault(key, fallback); }
    private void failSearch() throws MediaPlatformException {
        if (fixture.containsKey("searchFailure")) throw new MediaPlatformException((String) fixture.get("searchFailure"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> candidates(String key) {
        return (List<Map<String, Object>>) fixture.getOrDefault(key, List.of());
    }

    private final class FakeSession implements MediaSessionPort.Session {
        private final MediaApp app;
        private String state, title = "初始曲目", artist = "合成歌手";
        private long position, queue;

        FakeSession(MediaApp app) {
            this.app = app;
            state = text(fixture, app.wireName() + "State", "PAUSED");
        }

        @Override public MediaSessionPort.Snapshot snapshot() {
            return new MediaSessionPort.Snapshot(state, 4L | 2L | 32L | 16L | 256L,
                    title, artist, "synthetic-" + queue, queue, position, 300_000L);
        }

        @Override public void send(MediaSessionPort.Action action, long positionMs) {
            switch (action) {
                case PLAY -> state = "PLAYING";
                case PAUSE -> state = "PAUSED";
                case NEXT -> queue++;
                case PREVIOUS -> queue--;
                case SEEK -> position = positionMs;
            }
            effect(app.wireName() + "." + action.name().toLowerCase(java.util.Locale.ROOT),
                    action == MediaSessionPort.Action.SEEK ? Map.of("positionMs", positionMs) : Map.of());
        }
    }

    private static String text(Map<String, Object> map, String key, String fallback) {
        return (String) map.getOrDefault(key, fallback);
    }
    private static int number(Map<String, Object> map, String key, int fallback) {
        return ((Number) map.getOrDefault(key, fallback)).intValue();
    }
}
