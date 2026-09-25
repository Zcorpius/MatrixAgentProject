package com.matrix.agent.platform.media;

import android.media.session.PlaybackState;
import android.os.SystemClock;

import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.contract.schema.BilibiliVideoSchema;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.task.capability.CapabilityProvider;
import com.matrix.agent.task.capability.MediaCapabilities;
import com.matrix.agent.task.tool.ToolResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Executes only the closed media catalog. All playback readback stays inside one Tool budget. */
public final class MediaCapabilityProvider implements CapabilityProvider, PendingMediaConfirmation,
        PendingBilibiliSelection {
    private static final long READBACK_POLL_MS = 100L;
    private static final long RETURN_MARGIN_MS = 250L;
    private static final long SEEK_TOLERANCE_MS = 2_000L;
    private static final long SEARCH_CONTEXT_TTL_MS = TimeUnit.MINUTES.toMillis(10);

    private final PackageProbe packages;
    private final MediaSessionPort sessions;
    private final AppLaunchPort launcher;
    private final QQMusicUiPort qqUi;
    private final BilibiliUiPort bilibiliUi;
    // CHM supports lock-free snapshots/discards. Writers use this monitor only to make
    // expiry eviction, capacity enforcement and insertion one bounded transaction.
    private final Map<String, SearchContext> searchContexts = new ConcurrentHashMap<>();
    private final Map<String, BilibiliSearchContext> bilibiliSearchContexts =
            new ConcurrentHashMap<>();

    private record SearchContext(QQMusicUiPort.SearchPage page, String requestId,
            String originalRequestText, int confirmableIndex, long createdElapsedMillis) {}
    private record BilibiliSearchContext(BilibiliUiPort.SearchPage page, String requestId,
            String originalRequestText, long createdElapsedMillis) {}

    public MediaCapabilityProvider(PackageProbe packages, MediaSessionPort sessions,
            AppLaunchPort launcher) {
        this(packages, sessions, launcher, null);
    }

    public MediaCapabilityProvider(PackageProbe packages, MediaSessionPort sessions,
            AppLaunchPort launcher, QQMusicUiPort qqUi) {
        this(packages, sessions, launcher, qqUi, null);
    }

    public MediaCapabilityProvider(PackageProbe packages, MediaSessionPort sessions,
            AppLaunchPort launcher, QQMusicUiPort qqUi, BilibiliUiPort bilibiliUi) {
        this.packages = Objects.requireNonNull(packages, "packages");
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.qqUi = qqUi;
        this.bilibiliUi = bilibiliUi;
    }

    public static Set<String> capabilities() { return MediaCapabilities.ALL; }

    @Override public boolean hasPendingConfirmation(String sessionId) {
        return snapshot(sessionId).filter(value -> value.confirmableIndex() > 0).isPresent();
    }

    @Override public Optional<PendingMediaConfirmation.Snapshot> snapshot(String sessionId) {
        if (sessionId == null) return Optional.empty();
        SearchContext context = searchContexts.get(sessionId);
        if (context == null) return Optional.empty();
        if (SystemClock.elapsedRealtime() - context.createdElapsedMillis()
                > SEARCH_CONTEXT_TTL_MS) {
            searchContexts.remove(sessionId, context);
            return Optional.empty();
        }
        return Optional.of(new PendingMediaConfirmation.Snapshot(context.page().candidates(),
                context.confirmableIndex(), context.originalRequestText()));
    }

    @Override public void discard(String sessionId) {
        if (sessionId != null) searchContexts.remove(sessionId);
    }

    @Override public Optional<PendingBilibiliSelection.Snapshot> bilibiliSnapshot(
            String sessionId) {
        if (sessionId == null) return Optional.empty();
        BilibiliSearchContext context = bilibiliSearchContexts.get(sessionId);
        if (context == null) return Optional.empty();
        if (SystemClock.elapsedRealtime() - context.createdElapsedMillis()
                > SEARCH_CONTEXT_TTL_MS) {
            bilibiliSearchContexts.remove(sessionId, context);
            return Optional.empty();
        }
        return Optional.of(new PendingBilibiliSelection.Snapshot(
                context.page().candidates(), context.originalRequestText()));
    }

    @Override public void discardBilibili(String sessionId) {
        if (sessionId != null) bilibiliSearchContexts.remove(sessionId);
    }

    @Override public ToolResult execute(AgentRequest request, ToolCall call) {
        final long started = System.nanoTime();
        final String capability = call.getCapabilityName();
        if (!MediaCapabilities.ALL.contains(capability)) {
            return result(call, ToolResult.Status.EXECUTION_FAILED, "ACTION_UNSUPPORTED",
                    null, null, false, started);
        }
        MediaApp app = capability.startsWith("media.qqmusic.")
                ? MediaApp.QQMUSIC : MediaApp.BILIBILI;
        if (!packages.installedAndEnabled(app)) {
            return result(call, ToolResult.Status.EXECUTION_FAILED, "APP_NOT_INSTALLED",
                    app, "NOT_SENT", false, started);
        }
        try {
            if (MediaCapabilities.QQ_OPEN.equals(capability)) {
                launcher.openApp(app);
                return result(call, ToolResult.Status.SUCCESS, null, app, "REQUESTED",
                        false, started);
            }
            if (MediaCapabilities.BILI_OPEN.equals(capability)) {
                Object rawBvid = call.argument("bvid");
                Object rawPage = call.argument("page");
                if (!(rawBvid instanceof String)
                        || !BilibiliVideoSchema.valid((String) rawBvid, rawPage)) {
                    return result(call, ToolResult.Status.EXECUTION_FAILED, "INVALID_ARGUMENT",
                            app, "NOT_SENT", false, started);
                }
                Integer page = rawPage == null ? null : ((Number) rawPage).intValue();
                launcher.openBilibiliVideo((String) rawBvid, page);
                return result(call, ToolResult.Status.SUCCESS, null, app, "REQUESTED",
                        false, started);
            }
            if (MediaCapabilities.QQ_SEARCH.equals(capability)) {
                return searchSongs(request, call, started);
            }
            if (MediaCapabilities.QQ_PLAY_RESULT.equals(capability)) {
                return playSearchResult(request, call, started);
            }
            if (MediaCapabilities.BILI_SEARCH.equals(capability)) {
                return searchBilibili(request, call, started);
            }
            if (MediaCapabilities.BILI_OPEN_RESULT.equals(capability)) {
                return openBilibiliResult(request, call, started);
            }
            if (MediaCapabilities.QQ_PLAY.equals(capability)
                    && hasPendingConfirmation(request.getSessionId())
                    && MediaSelectionUtterance.isAffirmative(request.getText())) {
                return result(call, ToolResult.Status.EXECUTION_FAILED,
                        "SELECTION_REQUIRES_RESULT", app, "NOT_SENT", false, started);
            }
            MediaSessionPort.Session session = sessions.find(app);
            MediaSessionPort.Snapshot before = session.snapshot();
            if (MediaCapabilities.QQ_STATE.equals(capability)
                    || MediaCapabilities.BILI_STATE.equals(capability)) {
                return stateResult(call, app, before, started);
            }
            return control(request, call, app, session, before, started);
        } catch (MediaPlatformException failure) {
            return result(call, ToolResult.Status.EXECUTION_FAILED, failure.code(), app,
                    "NOT_SENT", false, started);
        } catch (SecurityException failure) {
            return result(call, ToolResult.Status.EXECUTION_FAILED, "MEDIA_CONTROL_UNAVAILABLE",
                    app, "NOT_SENT", false, started);
        }
    }

    private ToolResult searchSongs(AgentRequest request, ToolCall call, long started)
            throws MediaPlatformException {
        if (qqUi == null) throw new MediaPlatformException("UI_ACCESS_NOT_ENABLED");
        Object raw = call.argument("query");
        if (!(raw instanceof String) || !validQuery((String) raw)) {
            return result(call, ToolResult.Status.EXECUTION_FAILED, "INVALID_ARGUMENT",
                    MediaApp.QQMUSIC, "NOT_SENT", false, started);
        }
        if (request.isCancelled() || request.remainingMillis() <= RETURN_MARGIN_MS + 500L) {
            throw new MediaPlatformException("NOT_DISPATCHED");
        }
        String query = ((String) raw).strip();
        QQMusicUiPort.SearchPage page = qqUi.search(query, uiDeadline(request,
                MediaCapabilities.QQ_SEARCH));
        rememberSearch(request, page);
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (QQMusicUiPort.Candidate candidate : page.candidates()) {
            candidates.add(Map.of("index", candidate.index(), "title", candidate.title(),
                    "detail", candidate.detail()));
        }
        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("media.app", MediaApp.QQMUSIC.wireName());
        observed.put("media.query", query);
        observed.put("media.candidates", candidates);
        int confirmableIndex = confirmableIndex(request.getText(), page.candidates());
        if (confirmableIndex > 0) observed.put("media.confirmable_index", confirmableIndex);
        observed.put("media.dispatch_state", "CONFIRMED");
        return new ToolResult(ToolResult.Status.SUCCESS, call.getCapabilityName(),
                confirmableIndex > 0
                        ? "已找到唯一匹配歌曲，等待用户确认是否播放"
                        : "已找到 " + candidates.size() + " 首候选歌曲，等待用户选择",
                observed, true, elapsed(started));
    }

    private ToolResult searchBilibili(AgentRequest request, ToolCall call, long started)
            throws MediaPlatformException {
        if (bilibiliUi == null) throw new MediaPlatformException("UI_ACCESS_NOT_ENABLED");
        Object raw = call.argument("query");
        if (!(raw instanceof String) || !validQuery((String) raw)) {
            return result(call, ToolResult.Status.EXECUTION_FAILED, "INVALID_ARGUMENT",
                    MediaApp.BILIBILI, "NOT_SENT", false, started);
        }
        if (request.isCancelled() || request.remainingMillis() <= RETURN_MARGIN_MS + 500L) {
            throw new MediaPlatformException("NOT_DISPATCHED");
        }
        String query = ((String) raw).strip();
        BilibiliUiPort.SearchPage page = bilibiliUi.search(query,
                uiDeadline(request, MediaCapabilities.BILI_SEARCH));
        rememberBilibiliSearch(request, page);
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (BilibiliUiPort.Candidate candidate : page.candidates()) {
            candidates.add(Map.of("index", candidate.index(), "title", candidate.title(),
                    "kind", candidate.kind(), "detail", candidate.detail()));
        }
        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("media.app", MediaApp.BILIBILI.wireName());
        observed.put("media.query", query);
        observed.put("media.candidates", candidates);
        observed.put("media.dispatch_state", "CONFIRMED");
        return new ToolResult(ToolResult.Status.SUCCESS, call.getCapabilityName(),
                "已找到 " + candidates.size() + " 个候选条目，等待用户选择",
                observed, true, elapsed(started));
    }

    private ToolResult openBilibiliResult(AgentRequest request, ToolCall call, long started)
            throws MediaPlatformException {
        if (bilibiliUi == null) throw new MediaPlatformException("UI_ACCESS_NOT_ENABLED");
        if (request.isCancelled() || request.remainingMillis() <= RETURN_MARGIN_MS + 500L) {
            throw new MediaPlatformException("NOT_DISPATCHED");
        }
        String sessionId = request.getSessionId();
        BilibiliSearchContext context = bilibiliSearchContexts.get(sessionId);
        if (context == null || SystemClock.elapsedRealtime() - context.createdElapsedMillis()
                > SEARCH_CONTEXT_TTL_MS) {
            bilibiliSearchContexts.remove(sessionId);
            throw new MediaPlatformException("SEARCH_CONTEXT_EXPIRED");
        }
        Object raw = call.argument("index");
        if (!(raw instanceof Number) || ((Number) raw).doubleValue()
                != Math.rint(((Number) raw).doubleValue())) {
            throw new MediaPlatformException("INVALID_ARGUMENT");
        }
        int index = ((Number) raw).intValue();
        if (index < 1 || index > context.page().candidates().size()) {
            throw new MediaPlatformException("INVALID_ARGUMENT");
        }
        BilibiliUiPort.Candidate candidate = context.page().candidates().get(index - 1);
        if (context.requestId().equals(request.getRequestId())
                || !explicitlySelectedBilibili(request.getText(), candidate,
                        context.page().candidates().size() == 1)) {
            throw new MediaPlatformException("SELECTION_NOT_CONFIRMED");
        }
        bilibiliUi.open(context.page(), candidate,
                uiDeadline(request, MediaCapabilities.BILI_OPEN_RESULT));
        bilibiliSearchContexts.remove(sessionId, context);
        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("media.app", MediaApp.BILIBILI.wireName());
        observed.put("media.dispatch_state", "REQUESTED");
        return new ToolResult(ToolResult.Status.SUCCESS, call.getCapabilityName(),
                "已打开所选结果；是否开始播放尚未确认", observed, false, elapsed(started));
    }

    private static boolean explicitlySelectedBilibili(String utterance,
            BilibiliUiPort.Candidate candidate, boolean onlyCandidate) {
        if (onlyCandidate && MediaSelectionUtterance.isAffirmative(utterance)) return true;
        if (MediaSelectionUtterance.indexChoice(utterance) == candidate.index()) return true;
        String clean = utterance.strip().replaceAll("[，,。.!！]+$", "")
                .replaceAll("^(?:选|打开|播放)", "").strip()
                .replaceAll("^[《〈]", "").replaceAll("[》〉]$", "");
        return clean.equals(candidate.title());
    }

    private synchronized void rememberBilibiliSearch(AgentRequest request,
            BilibiliUiPort.SearchPage page) {
        long now = SystemClock.elapsedRealtime();
        bilibiliSearchContexts.entrySet().removeIf(entry ->
                now - entry.getValue().createdElapsedMillis() > SEARCH_CONTEXT_TTL_MS);
        if (bilibiliSearchContexts.size() >= 32
                && !bilibiliSearchContexts.containsKey(request.getSessionId())) {
            String oldest = bilibiliSearchContexts.entrySet().stream()
                    .min((a, b) -> Long.compare(a.getValue().createdElapsedMillis(),
                            b.getValue().createdElapsedMillis()))
                    .map(Map.Entry::getKey).orElse(null);
            if (oldest != null) bilibiliSearchContexts.remove(oldest);
        }
        bilibiliSearchContexts.put(request.getSessionId(), new BilibiliSearchContext(page,
                request.getRequestId(), request.getText(), now));
    }

    private ToolResult playSearchResult(AgentRequest request, ToolCall call, long started)
            throws MediaPlatformException {
        if (qqUi == null) throw new MediaPlatformException("UI_ACCESS_NOT_ENABLED");
        if (request.remainingMillis() <= RETURN_MARGIN_MS + 500L || request.isCancelled()) {
            throw new MediaPlatformException("NOT_DISPATCHED");
        }
        SearchContext context = searchContexts.get(request.getSessionId());
        if (context == null || SystemClock.elapsedRealtime() - context.createdElapsedMillis()
                > SEARCH_CONTEXT_TTL_MS) {
            searchContexts.remove(request.getSessionId());
            throw new MediaPlatformException("SEARCH_CONTEXT_EXPIRED");
        }
        Object raw = call.argument("index");
        if (!(raw instanceof Number) || ((Number) raw).doubleValue() !=
                Math.rint(((Number) raw).doubleValue())) {
            throw new MediaPlatformException("INVALID_ARGUMENT");
        }
        int index = ((Number) raw).intValue();
        if (index < 1 || index > context.page().candidates().size()) {
            throw new MediaPlatformException("INVALID_ARGUMENT");
        }
        QQMusicUiPort.Candidate selected = context.page().candidates().get(index - 1);
        boolean uniqueTitle = context.page().candidates().stream()
                .filter(candidate -> candidate.title().equals(selected.title())).count() == 1L;
        if (context.requestId().equals(request.getRequestId())
                || !explicitlySelected(request.getText(), selected,
                        index == context.confirmableIndex(), uniqueTitle)) {
            throw new MediaPlatformException("SELECTION_NOT_CONFIRMED");
        }
        long deadline = uiDeadline(request, MediaCapabilities.QQ_PLAY_RESULT);
        qqUi.select(context.page(), selected, deadline);
        searchContexts.remove(request.getSessionId(), context);
        MediaSessionPort.Snapshot latest = null;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (request.isCancelled() || Thread.currentThread().isInterrupted()) {
                return result(call, ToolResult.Status.EXECUTION_UNKNOWN,
                        "CANCELLED_AFTER_DISPATCH", MediaApp.QQMUSIC, "REQUESTED", false,
                        started);
            }
            try {
                latest = sessions.find(MediaApp.QQMUSIC).snapshot();
                if ("PLAYING".equals(latest.playbackState)
                        && selected.title().equals(latest.title)
                        && selectedArtistMatches(selected, latest.artist)) {
                    return verified(call, MediaApp.QQMUSIC, latest, started);
                }
            } catch (MediaPlatformException noSessionYet) {
                // The session may appear after the UI click; retain the bounded readback window.
            }
            try {
                Thread.sleep(READBACK_POLL_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return result(call, ToolResult.Status.EXECUTION_UNKNOWN,
                        "CANCELLED_AFTER_DISPATCH", MediaApp.QQMUSIC, "REQUESTED", false,
                        started);
            }
        }
        Map<String, Object> observed = latest == null ? new LinkedHashMap<>()
                : observed(MediaApp.QQMUSIC, latest);
        observed.put("media.dispatch_state", "REQUESTED");
        observed.put("media.error_code", "PLAYBACK_NOT_VERIFIED");
        return new ToolResult(ToolResult.Status.VERIFICATION_FAILED,
                call.getCapabilityName(), "所选歌曲未能在回读窗口内确认播放",
                observed, false, elapsed(started));
    }

    private static boolean validQuery(String query) {
        String clean = query.strip();
        if (clean.isEmpty() || clean.length() > 64) return false;
        for (int i = 0; i < clean.length(); i++) {
            if (Character.isISOControl(clean.charAt(i))) return false;
        }
        return true;
    }

    private synchronized void rememberSearch(AgentRequest request, QQMusicUiPort.SearchPage page) {
        long now = SystemClock.elapsedRealtime();
        searchContexts.entrySet().removeIf(entry ->
                now - entry.getValue().createdElapsedMillis() > SEARCH_CONTEXT_TTL_MS);
        if (searchContexts.size() >= 32 && !searchContexts.containsKey(request.getSessionId())) {
            String oldest = searchContexts.entrySet().stream()
                    .min((a, b) -> Long.compare(a.getValue().createdElapsedMillis(),
                            b.getValue().createdElapsedMillis()))
                    .map(Map.Entry::getKey).orElse(null);
            if (oldest != null) searchContexts.remove(oldest);
        }
        searchContexts.put(request.getSessionId(), new SearchContext(page,
                request.getRequestId(), request.getText(),
                confirmableIndex(request.getText(), page.candidates()), now));
    }

    private static int confirmableIndex(String requestText,
            List<QQMusicUiPort.Candidate> candidates) {
        int match = 0;
        for (QQMusicUiPort.Candidate candidate : candidates) {
            String title = candidate.title().strip();
            String artist = primaryArtist(candidate.detail());
            if (title.length() < 2 || artist.length() < 2
                    || !requestText.contains(title) || !requestText.contains(artist)) continue;
            if (match != 0) return 0;
            match = candidate.index();
        }
        return match;
    }

    private static String primaryArtist(String detail) {
        int divider = detail.indexOf('·');
        return (divider < 0 ? detail : detail.substring(0, divider)).strip();
    }

    private static boolean explicitlySelected(String text, QQMusicUiPort.Candidate candidate,
            boolean affirmativeAllowed, boolean uniqueTitle) {
        if (affirmativeAllowed && MediaSelectionUtterance.isAffirmative(text)) return true;
        if (MediaSelectionUtterance.indexChoice(text) == candidate.index()) return true;
        String clean = text.strip().replaceAll("[，,。.!！]+$", "")
                .replaceAll("^(?:选|播放)", "").strip()
                .replaceAll("^[《〈]", "").replaceAll("[》〉]$", "");
        return uniqueTitle && clean.equals(candidate.title());
    }


    private static boolean selectedArtistMatches(QQMusicUiPort.Candidate selected,
            String observedArtist) {
        String detail = selected.detail();
        int divider = detail.indexOf('·');
        if (divider < 1 || observedArtist == null) return false;
        String expected = detail.substring(0, divider).strip();
        return observedArtist.contains(expected);
    }

    private static long uiDeadline(AgentRequest request, String capability) {
        long budget = Math.min(timeoutFor(capability), request.remainingMillis());
        return SystemClock.elapsedRealtime() + Math.max(0L, budget - RETURN_MARGIN_MS);
    }

    private ToolResult control(AgentRequest request, ToolCall call, MediaApp app,
            MediaSessionPort.Session session, MediaSessionPort.Snapshot before, long started) {
        MediaSessionPort.Action action = actionOf(call.getCapabilityName());
        if (action == null) {
            return result(call, ToolResult.Status.EXECUTION_FAILED, "ACTION_UNSUPPORTED",
                    app, "NOT_SENT", false, started);
        }
        long position = -1L;
        if (action == MediaSessionPort.Action.SEEK) {
            Object raw = call.argument("position_ms");
            if (!(raw instanceof Number) || before.durationMs <= 0) {
                return result(call, ToolResult.Status.EXECUTION_FAILED, "DURATION_UNKNOWN",
                        app, "NOT_SENT", false, started);
            }
            double value = ((Number) raw).doubleValue();
            if (!Double.isFinite(value) || value != Math.rint(value)
                    || value < 0 || value > before.durationMs) {
                return result(call, ToolResult.Status.EXECUTION_FAILED, "INVALID_ARGUMENT",
                        app, "NOT_SENT", false, started);
            }
            position = (long) value;
        }
        if (alreadySatisfied(action, before, position)) {
            return verified(call, app, before, started);
        }
        long requiredAction = actionBit(action);
        if ((before.actions & requiredAction) == 0L) {
            return result(call, ToolResult.Status.EXECUTION_FAILED, "ACTION_UNSUPPORTED",
                    app, "NOT_SENT", false, started);
        }
        if (request.isCancelled() || request.remainingMillis() <= RETURN_MARGIN_MS
                || Thread.currentThread().isInterrupted()) {
            return result(call, ToolResult.Status.EXECUTION_FAILED, "NOT_DISPATCHED",
                    app, "NOT_SENT", false, started);
        }
        try {
            session.send(action, position);
        } catch (RuntimeException failure) {
            // TransportControls has no acknowledgement. A failure while dispatching may occur
            // after the remote side saw the command; keep the state unknown.
            return result(call, ToolResult.Status.EXECUTION_UNKNOWN, "DISPATCH_UNCERTAIN",
                    app, "REQUESTED", false, started);
        }

        long capBudget = timeoutFor(call.getCapabilityName()) - RETURN_MARGIN_MS;
        long remaining = Math.min(capBudget, request.remainingMillis() - RETURN_MARGIN_MS);
        if (remaining <= 0) {
            return result(call, ToolResult.Status.EXECUTION_UNKNOWN, "DEADLINE_EXPIRED",
                    app, "REQUESTED", false, started);
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(remaining);
        MediaSessionPort.Snapshot latest = before;
        while (System.nanoTime() < deadline) {
            if (request.isCancelled() || Thread.currentThread().isInterrupted()) {
                return result(call, ToolResult.Status.EXECUTION_UNKNOWN, "CANCELLED_AFTER_DISPATCH",
                        app, "REQUESTED", false, started);
            }
            try {
                latest = session.snapshot();
            } catch (RuntimeException failure) {
                return result(call, ToolResult.Status.VERIFICATION_FAILED,
                        "PLAYBACK_NOT_VERIFIED", app, "REQUESTED", false, started);
            }
            if (reached(action, before, latest, position)) {
                return verified(call, app, latest, started);
            }
            long sleepMs = Math.min(READBACK_POLL_MS,
                    TimeUnit.NANOSECONDS.toMillis(Math.max(0L, deadline - System.nanoTime())));
            if (sleepMs <= 0) break;
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return result(call, ToolResult.Status.EXECUTION_UNKNOWN,
                        "CANCELLED_AFTER_DISPATCH", app, "REQUESTED", false, started);
            }
        }
        Map<String, Object> observed = observed(app, latest);
        observed.put("media.dispatch_state", "REQUESTED");
        observed.put("media.error_code", "PLAYBACK_NOT_VERIFIED");
        return new ToolResult(ToolResult.Status.VERIFICATION_FAILED,
                call.getCapabilityName(), "媒体操作未能在回读窗口内确认",
                observed, false, elapsed(started));
    }

    private static ToolResult stateResult(ToolCall call, MediaApp app,
            MediaSessionPort.Snapshot snapshot, long started) {
        return new ToolResult(ToolResult.Status.SUCCESS, call.getCapabilityName(),
                "已查询媒体状态", observed(app, snapshot), false, elapsed(started));
    }

    private static ToolResult verified(ToolCall call, MediaApp app,
            MediaSessionPort.Snapshot snapshot, long started) {
        Map<String, Object> observed = observed(app, snapshot);
        observed.put("media.dispatch_state", "CONFIRMED");
        return new ToolResult(ToolResult.Status.SUCCESS, call.getCapabilityName(),
                "媒体操作已确认", observed, true, elapsed(started));
    }

    private static ToolResult result(ToolCall call, ToolResult.Status status, String code,
            MediaApp app, String dispatch, boolean verified, long started) {
        Map<String, Object> observed = new LinkedHashMap<>();
        if (app != null) observed.put("media.app", app.wireName());
        if (dispatch != null) observed.put("media.dispatch_state", dispatch);
        if (code != null) observed.put("media.error_code", code);
        if ("APP_NOT_INSTALLED".equals(code)) observed.put("media.installed", false);
        String message = status == ToolResult.Status.SUCCESS
                ? "媒体启动请求已提交" : "媒体操作未完成：" + (code == null ? status.name() : code);
        return new ToolResult(status, call.getCapabilityName(), message, observed,
                verified, elapsed(started));
    }

    private static Map<String, Object> observed(MediaApp app, MediaSessionPort.Snapshot snapshot) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("media.app", app.wireName());
        state.put("media.installed", true);
        state.put("media.session_state", "ACTIVE");
        state.put("media.playback_state", snapshot.playbackState);
        state.put("media.supported_actions", actionNames(snapshot.actions));
        if (snapshot.title != null) state.put("media.title", snapshot.title);
        if (snapshot.artist != null) state.put("media.artist", snapshot.artist);
        if (snapshot.mediaId != null) state.put("media.media_id", snapshot.mediaId);
        if (snapshot.queueItemId >= 0) state.put("media.queue_item_id", snapshot.queueItemId);
        if (snapshot.positionMs >= 0) state.put("media.position_ms", snapshot.positionMs);
        if (snapshot.durationMs > 0) state.put("media.duration_ms", snapshot.durationMs);
        state.put("media.observed_at_ms", System.currentTimeMillis());
        return state;
    }

    private static List<String> actionNames(long flags) {
        List<String> names = new ArrayList<>(5);
        for (MediaSessionPort.Action action : MediaSessionPort.Action.values()) {
            if ((flags & actionBit(action)) != 0) names.add(action.name());
        }
        return names;
    }

    private static boolean alreadySatisfied(MediaSessionPort.Action action,
            MediaSessionPort.Snapshot state, long position) {
        return action == MediaSessionPort.Action.PLAY && "PLAYING".equals(state.playbackState)
                || action == MediaSessionPort.Action.PAUSE && "PAUSED".equals(state.playbackState)
                || action == MediaSessionPort.Action.SEEK
                        && state.positionMs >= 0
                        && Math.abs(state.positionMs - position) <= SEEK_TOLERANCE_MS;
    }

    private static boolean reached(MediaSessionPort.Action action,
            MediaSessionPort.Snapshot before, MediaSessionPort.Snapshot after, long position) {
        switch (action) {
            case PLAY: return "PLAYING".equals(after.playbackState);
            case PAUSE: return "PAUSED".equals(after.playbackState);
            case SEEK:
                return after.positionMs >= 0
                        && Math.abs(after.positionMs - position) <= SEEK_TOLERANCE_MS;
            case NEXT:
            case PREVIOUS:
                return before.mediaId != null && after.mediaId != null
                        && !before.mediaId.equals(after.mediaId)
                        || before.queueItemId >= 0 && after.queueItemId >= 0
                        && before.queueItemId != after.queueItemId;
            default: return false;
        }
    }

    private static MediaSessionPort.Action actionOf(String capability) {
        switch (capability) {
            case MediaCapabilities.QQ_PLAY:
            case MediaCapabilities.BILI_RESUME: return MediaSessionPort.Action.PLAY;
            case MediaCapabilities.QQ_PAUSE:
            case MediaCapabilities.BILI_PAUSE: return MediaSessionPort.Action.PAUSE;
            case MediaCapabilities.QQ_NEXT: return MediaSessionPort.Action.NEXT;
            case MediaCapabilities.QQ_PREVIOUS: return MediaSessionPort.Action.PREVIOUS;
            case MediaCapabilities.QQ_SEEK: return MediaSessionPort.Action.SEEK;
            default: return null;
        }
    }

    private static long actionBit(MediaSessionPort.Action action) {
        switch (action) {
            case PLAY: return PlaybackState.ACTION_PLAY;
            case PAUSE: return PlaybackState.ACTION_PAUSE;
            case NEXT: return PlaybackState.ACTION_SKIP_TO_NEXT;
            case PREVIOUS: return PlaybackState.ACTION_SKIP_TO_PREVIOUS;
            case SEEK: return PlaybackState.ACTION_SEEK_TO;
            default: return 0L;
        }
    }

    private static long timeoutFor(String capability) {
        switch (capability) {
            case MediaCapabilities.BILI_SEARCH:
            case MediaCapabilities.BILI_OPEN_RESULT: return 15_000L;
            case MediaCapabilities.QQ_SEARCH: return 12_000L;
            case MediaCapabilities.QQ_PLAY_RESULT: return 15_000L;
            case MediaCapabilities.QQ_PLAY:
            case MediaCapabilities.BILI_RESUME: return 6_000L;
            case MediaCapabilities.QQ_PAUSE:
            case MediaCapabilities.BILI_PAUSE:
            case MediaCapabilities.QQ_SEEK: return 4_000L;
            case MediaCapabilities.QQ_NEXT:
            case MediaCapabilities.QQ_PREVIOUS: return 2_500L;
            default: return 1_500L;
        }
    }

    private static long elapsed(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}
