package com.matrix.agent.platform.media;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Version-bound accessibility adapter. It reads only QQ Music's active tree and acts on exact
 * package-owned selectors; an app update or unexpected page shape fails closed.
 */
public final class AndroidQQMusicUiPort implements QQMusicUiPort {
    private static final String PACKAGE = "com.tencent.qqmusic";
    private static final String SEARCH_ENTRY = PACKAGE + ":id/c22";
    private static final String SEARCH_INPUT = PACKAGE + ":id/searchItem";
    private static final String SEARCH_TAB = PACKAGE + ":id/e3o";
    private static final String SEARCH_SUGGESTION = PACKAGE + ":id/jr4";
    private static final String SONG_ROW = PACKAGE + ":id/item_layout";
    private static final String SONG_TITLE = PACKAGE + ":id/kk8";
    private static final String SONG_DETAIL = PACKAGE + ":id/kma";
    private static final String PLAYER_BACK = PACKAGE + ":id/m19";
    private static final int MAX_CANDIDATES = 8;
    private static final long POLL_MILLIS = 120L;
    private static final long PAGE_SETTLE_MILLIS = 3_000L;

    private final Context context;
    private final AppLaunchPort launcher;
    private final ReentrantLock uiLock = new ReentrantLock();

    public AndroidQQMusicUiPort(Context context, AppLaunchPort launcher) {
        this.context = context.getApplicationContext();
        this.launcher = launcher;
    }

    @Override public SearchPage search(String query, LaunchContext ctx)
            throws MediaPlatformException {
        long deadlineElapsedMillis = ctx.deadlineElapsedMillis();
        ctx.checkActive();
        lockUntil(deadlineElapsedMillis, ctx);
        try {
            return searchLocked(query, deadlineElapsedMillis, ctx);
        } finally {
            uiLock.unlock();
        }
    }

    @Override public void select(SearchPage page, Candidate candidate, LaunchContext ctx)
            throws MediaPlatformException {
        long deadlineElapsedMillis = ctx.deadlineElapsedMillis();
        ctx.checkActive();
        lockUntil(deadlineElapsedMillis, ctx);
        try {
            if (appVersion() != page.appVersion()) {
                throw new MediaPlatformException("SEARCH_CONTEXT_EXPIRED");
            }
            SearchPage refreshed = searchLocked(page.query(), deadlineElapsedMillis, ctx);
            if (refreshed.appVersion() != page.appVersion()) {
                throw new MediaPlatformException("SEARCH_CONTEXT_EXPIRED");
            }
            long matches = refreshed.candidates().stream()
                    .filter(found -> found.title().equals(candidate.title())
                            && found.detail().equals(candidate.detail())).count();
            if (matches != 1) throw new MediaPlatformException("SEARCH_RESULT_CHANGED");
            QQMusicAccessibilityService service = service();
            AccessibilityNodeInfo root = requireRoot(service, deadlineElapsedMillis, ctx);
            if (!page.query().contentEquals(searchText(root))) {
                throw new MediaPlatformException("SEARCH_RESULT_CHANGED");
            }
            for (AccessibilityNodeInfo row : root.findAccessibilityNodeInfosByViewId(SONG_ROW)) {
                if (!row.isVisibleToUser() || !row.isClickable()) continue;
                Candidate live = candidateOf(row, candidate.index());
                if (live != null && live.title().equals(candidate.title())
                        && live.detail().equals(candidate.detail())) {
                    ctx.checkActive();
                    if (!row.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        throw new MediaPlatformException("UI_ACTION_REJECTED");
                    }
                    return;
                }
            }
            throw new MediaPlatformException("SEARCH_RESULT_CHANGED");
        } finally {
            uiLock.unlock();
        }
    }

    private SearchPage searchLocked(String query, long deadline, LaunchContext ctx) throws MediaPlatformException {
        QQMusicAccessibilityService service = service();
        long version = appVersion();
        if (service.qqRoot() == null) launcher.openApp(MediaApp.QQMUSIC, ctx);
        AccessibilityNodeInfo root = openSearchSurface(service, deadline, ctx);
        if (first(root, SEARCH_INPUT) == null) {
            AccessibilityNodeInfo entry = first(root, SEARCH_ENTRY);
            if (entry == null || !click(entry, ctx)) {
                throw new MediaPlatformException("SEARCH_UI_CHANGED");
            }
        }
        AccessibilityNodeInfo input = waitForNode(service, SEARCH_INPUT, deadline, ctx);
        AccessibilityNodeInfo before = requireRoot(service, deadline, ctx);
        String previousQuery = searchText(before);
        List<Candidate> previousCandidates = candidates(before);
        Bundle text = new Bundle();
        text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query);
        ctx.checkActive();
        if (!input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, text)) {
            throw new MediaPlatformException("UI_ACTION_REJECTED");
        }
        // Prefer a package-owned suggestion. IME_ENTER is only valid with existing target focus;
        // never acquire target input focus while the user is editing the Agent panel.
        boolean submitted = clickVisibleSuggestion(service, query, ctx);
        if (!submitted && service.canUseFocusedInput(MediaApp.QQMUSIC)) {
            // Re-enter the target's own search form only while that app owns interaction.
            // A focused Agent editor makes this guard false, so its IME is never stolen.
            if (!input.isFocused() && input.isClickable()) {
                ctx.checkActive();
                input.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                input = waitForNode(service, SEARCH_INPUT, deadline, ctx);
            }
            submitted = clickVisibleSuggestion(service, query, ctx);
            if (!submitted && android.os.Build.VERSION.SDK_INT >= 30
                    && service.canUseFocusedInput(MediaApp.QQMUSIC) && input.isFocused()) {
                ctx.checkActive();
                submitted = input.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
            }
        }
        if (!submitted) clickExactSuggestion(service, query,
                Math.min(deadline, SystemClock.elapsedRealtime() + 1200), ctx);
        AccessibilityNodeInfo songsTab = waitForSongsTab(service, deadline, ctx);
        if (!songsTab.isSelected() && !click(songsTab, ctx)) {
            throw new MediaPlatformException("UI_ACTION_REJECTED");
        }
        List<Candidate> candidates = waitForCandidates(service, query, previousQuery,
                previousCandidates, deadline, ctx);
        return new SearchPage(query, version, List.copyOf(candidates));
    }

    private static AccessibilityNodeInfo openSearchSurface(QQMusicAccessibilityService service,
            long deadline, LaunchContext ctx) throws MediaPlatformException {
        // QQ Music keeps the full-screen player above the search page after a selection.
        // Leave only that recognized page, then wait for the previous search surface.
        AccessibilityNodeInfo root = requireRoot(service, deadline, ctx);
        long settleUntil = Math.min(deadline,
                SystemClock.elapsedRealtime() + PAGE_SETTLE_MILLIS);
        while (SystemClock.elapsedRealtime() < settleUntil) {
            if (root != null) {
                if (first(root, SEARCH_INPUT) != null || first(root, SEARCH_ENTRY) != null) {
                    return root;
                }
                AccessibilityNodeInfo back = first(root, PLAYER_BACK);
                if (back != null && back.isVisibleToUser()
                        && "返回".contentEquals(back.getContentDescription())) {
                    if (!click(back, ctx)) throw new MediaPlatformException("UI_ACTION_REJECTED");
                    while (SystemClock.elapsedRealtime() < deadline) {
                        root = service.qqRoot();
                        if (root != null && (first(root, SEARCH_INPUT) != null
                                || first(root, SEARCH_ENTRY) != null)) return root;
                        pause(ctx);
                    }
                    throw new MediaPlatformException("SEARCH_UI_CHANGED");
                }
            }
            pause(ctx);
            root = service.qqRoot();
        }
        throw new MediaPlatformException("SEARCH_UI_CHANGED");
    }

    private void lockUntil(long deadline, LaunchContext ctx) throws MediaPlatformException {
        ctx.checkActive();
        try {
            long remaining = Math.max(0L, deadline - SystemClock.elapsedRealtime());
            if (!acquire(uiLock, ctx)) {
                throw new MediaPlatformException("UI_BUSY");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new MediaPlatformException("CANCELLED");
        }
    }

    private static boolean acquire(ReentrantLock lock, LaunchContext ctx)
            throws InterruptedException, MediaPlatformException {
        while (true) {
            ctx.checkActive();
            long wait = Math.min(50L, Math.max(1L, ctx.deadlineElapsedMillis() - SystemClock.elapsedRealtime()));
            if (lock.tryLock(wait, TimeUnit.MILLISECONDS)) return true;
        }
    }

    private long appVersion() throws MediaPlatformException {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(PACKAGE, 0);
            return info.getLongVersionCode();
        } catch (PackageManager.NameNotFoundException missing) {
            throw new MediaPlatformException("APP_NOT_INSTALLED");
        }
    }

    private static QQMusicAccessibilityService service() throws MediaPlatformException {
        QQMusicAccessibilityService service = QQMusicAccessibilityService.connected();
        if (service == null) throw new MediaPlatformException("UI_ACCESS_NOT_ENABLED");
        return service;
    }

    private static AccessibilityNodeInfo requireRoot(QQMusicAccessibilityService service,
            long deadline, LaunchContext ctx) throws MediaPlatformException {
        while (SystemClock.elapsedRealtime() < deadline) {
            AccessibilityNodeInfo root = service.qqRoot();
            if (root != null) return root;
            pause(ctx);
        }
        throw new MediaPlatformException("QQMUSIC_NOT_FOREGROUND");
    }

    private static AccessibilityNodeInfo waitForNode(QQMusicAccessibilityService service,
            String id, long deadline, LaunchContext ctx) throws MediaPlatformException {
        while (SystemClock.elapsedRealtime() < deadline) {
            AccessibilityNodeInfo root = service.qqRoot();
            AccessibilityNodeInfo node = root == null ? null : first(root, id);
            if (node != null) return node;
            pause(ctx);
        }
        throw new MediaPlatformException("SEARCH_UI_CHANGED");
    }

    private static AccessibilityNodeInfo waitForSongsTab(QQMusicAccessibilityService service,
            long deadline, LaunchContext ctx) throws MediaPlatformException {
        while (SystemClock.elapsedRealtime() < deadline) {
            AccessibilityNodeInfo root = service.qqRoot();
            if (root != null) {
                for (AccessibilityNodeInfo tab : root.findAccessibilityNodeInfosByViewId(SEARCH_TAB)) {
                    if ("歌曲".contentEquals(tab.getText()) && tab.isVisibleToUser()) return tab;
                }
            }
            pause(ctx);
        }
        throw new MediaPlatformException("SEARCH_UI_CHANGED");
    }

    private static boolean clickVisibleSuggestion(QQMusicAccessibilityService service,
            String query, LaunchContext ctx) throws MediaPlatformException {
        AccessibilityNodeInfo root = service.qqRoot();
        if (root == null) return false;
        for (AccessibilityNodeInfo suggestion : root.findAccessibilityNodeInfosByViewId(SEARCH_SUGGESTION)) {
            if (query.equals(clean(suggestion.getText())) && suggestion.isVisibleToUser()
                    && click(suggestion, ctx)) return true;
        }
        return false;
    }

    private static void clickExactSuggestion(QQMusicAccessibilityService service,
            String query, long deadline, LaunchContext ctx) throws MediaPlatformException {
        while (SystemClock.elapsedRealtime() < deadline) {
            AccessibilityNodeInfo root = service.qqRoot();
            if (root != null) {
                for (AccessibilityNodeInfo suggestion :
                        root.findAccessibilityNodeInfosByViewId(SEARCH_SUGGESTION)) {
                    if (query.equals(clean(suggestion.getText()))
                            && suggestion.isVisibleToUser() && click(suggestion, ctx)) return;
                }
            }
            pause(ctx);
        }
        throw new MediaPlatformException("SEARCH_UI_CHANGED");
    }

    private static List<Candidate> waitForCandidates(QQMusicAccessibilityService service,
            String query, String previousQuery, List<Candidate> previousCandidates, long deadline, LaunchContext ctx)
            throws MediaPlatformException {
        while (SystemClock.elapsedRealtime() < deadline) {
            AccessibilityNodeInfo root = service.qqRoot();
            if (root != null && query.contentEquals(searchText(root))) {
                List<Candidate> results = candidates(root);
                // A changed input can precede the network response. The prior result list must
                // change too; otherwise a matching row from the old query could be returned.
                boolean refreshed = query.equals(previousQuery)
                        || !results.equals(previousCandidates);
                if (refreshed && queryRepresented(query, results)) return results;
            }
            pause(ctx);
        }
        throw new MediaPlatformException("SEARCH_NO_RESULTS");
    }

    static boolean queryRepresented(String query, List<Candidate> results) {
        String[] terms = query.toLowerCase(Locale.ROOT).strip().split("\\s+");
        for (Candidate candidate : results) {
            String searchable = (candidate.title() + " " + candidate.detail())
                    .toLowerCase(Locale.ROOT);
            boolean allTermsFound = true;
            for (String term : terms) {
                if (!searchable.contains(term)) {
                    allTermsFound = false;
                    break;
                }
            }
            if (allTermsFound) return true;
        }
        return false;
    }

    private static List<Candidate> candidates(AccessibilityNodeInfo root) {
        List<Candidate> results = new ArrayList<>();
        for (AccessibilityNodeInfo row : root.findAccessibilityNodeInfosByViewId(SONG_ROW)) {
            if (!row.isVisibleToUser() || !row.isClickable()) continue;
            Candidate candidate = candidateOf(row, results.size() + 1);
            if (candidate != null) results.add(candidate);
            if (results.size() >= MAX_CANDIDATES) break;
        }
        return results;
    }

    private static Candidate candidateOf(AccessibilityNodeInfo row, int index) {
        AccessibilityNodeInfo title = first(row, SONG_TITLE);
        AccessibilityNodeInfo detail = first(row, SONG_DETAIL);
        String titleText = title == null ? "" : clean(title.getText());
        String detailText = detail == null ? "" : clean(detail.getText());
        return titleText.isEmpty() || detailText.isEmpty()
                ? null : new Candidate(index, titleText, detailText);
    }

    private static String searchText(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo node = first(root, SEARCH_INPUT);
        return node == null ? "" : clean(node.getText());
    }

    private static AccessibilityNodeInfo first(AccessibilityNodeInfo root, String id) {
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(id);
        return found.isEmpty() ? null : found.get(0);
    }

    private static boolean click(AccessibilityNodeInfo node, LaunchContext ctx) throws MediaPlatformException {
        ctx.checkActive();
        for (int i = 0; node != null && i < 5; i++, node = node.getParent()) {
            if (node.isClickable()) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        return false;
    }

    private static String clean(CharSequence text) {
        return text == null ? "" : text.toString().strip();
    }

    private static void pause(LaunchContext ctx) throws MediaPlatformException {
        if (Thread.currentThread().isInterrupted()) {
            throw new MediaPlatformException("CANCELLED");
        }
        ctx.checkActive();
        SystemClock.sleep(Math.min(POLL_MILLIS, Math.max(1L, ctx.deadlineElapsedMillis() - SystemClock.elapsedRealtime())));
        ctx.checkActive();
    }
}
