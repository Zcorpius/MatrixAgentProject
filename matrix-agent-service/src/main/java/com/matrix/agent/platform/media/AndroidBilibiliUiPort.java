package com.matrix.agent.platform.media;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Bilibili 9.12 search adapter. Every read and action is checked against its package tree. */
public final class AndroidBilibiliUiPort implements BilibiliUiPort {
    private static final String PACKAGE = MediaApp.BILIBILI.packageName();
    private static final String SEARCH_ENTRY = PACKAGE + ":id/expand_search";
    private static final String SEARCH_INPUT = PACKAGE + ":id/search_src_text";
    private static final String SEARCH_FAKE = PACKAGE + ":id/search_fake_text";
    private static final String SEARCH_BUTTON = PACKAGE + ":id/action_search";
    private static final String RESULTS = PACKAGE + ":id/result_container";
    private static final String OGV_ROW = PACKAGE + ":id/ogv_video_item_layout";
    private static final String OGV_TITLE = PACKAGE + ":id/ogv_item_relation_video_title";
    private static final String VIDEO_TITLE = PACKAGE + ":id/title";
    private static final String VIDEO_CREATOR = PACKAGE + ":id/upuser";
    private static final int MAX_CANDIDATES = 8;
    private static final long POLL_MILLIS = 120L;

    private final Context context;
    private final AppLaunchPort launcher;
    private final ReentrantLock lock = new ReentrantLock();

    private record LiveRow(Candidate candidate, AccessibilityNodeInfo node) {}

    public AndroidBilibiliUiPort(Context context, AppLaunchPort launcher) {
        this.context = context.getApplicationContext();
        this.launcher = launcher;
    }

    @Override public SearchPage search(String query, long deadlineElapsedMillis)
            throws MediaPlatformException {
        lockUntil(deadlineElapsedMillis);
        try {
            return searchLocked(query, deadlineElapsedMillis);
        } finally {
            lock.unlock();
        }
    }

    @Override public void open(SearchPage page, Candidate candidate, long deadlineElapsedMillis)
            throws MediaPlatformException {
        lockUntil(deadlineElapsedMillis);
        try {
            if (appVersion() != page.appVersion()) {
                throw new MediaPlatformException("SEARCH_CONTEXT_EXPIRED");
            }
            SearchPage refreshed = searchLocked(page.query(), deadlineElapsedMillis);
            if (refreshed.appVersion() != page.appVersion()) {
                throw new MediaPlatformException("SEARCH_CONTEXT_EXPIRED");
            }
            QQMusicAccessibilityService service = service();
            AccessibilityNodeInfo root = requireRoot(service, deadlineElapsedMillis);
            if (!page.query().equals(queryText(root))) {
                throw new MediaPlatformException("SEARCH_RESULT_CHANGED");
            }
            List<LiveRow> matches = liveRows(root).stream()
                    .filter(row -> BilibiliResultRules.sameResult(row.candidate(), candidate))
                    .toList();
            if (matches.size() != 1 || !click(matches.get(0).node())) {
                throw new MediaPlatformException("SEARCH_RESULT_CHANGED");
            }
        } finally {
            lock.unlock();
        }
    }

    private SearchPage searchLocked(String query, long deadline) throws MediaPlatformException {
        QQMusicAccessibilityService service = service();
        long version = appVersion();
        if (service.bilibiliRoot() == null) launcher.openApp(MediaApp.BILIBILI);
        AccessibilityNodeInfo root = navigateToSearchSurface(service, deadline);
        String previousQuery = queryText(root);
        List<Candidate> previousCandidates = candidates(root);
        AccessibilityNodeInfo input = first(root, SEARCH_INPUT);
        if (input == null) {
            AccessibilityNodeInfo entry = first(root, SEARCH_FAKE);
            if (entry == null) entry = first(root, SEARCH_ENTRY);
            if (entry == null || !click(entry)) {
                throw new MediaPlatformException("SEARCH_UI_CHANGED");
            }
            input = waitForNode(service, SEARCH_INPUT, deadline);
        }
        Bundle arguments = new Bundle();
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                query);
        if (!input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
            throw new MediaPlatformException("UI_ACTION_REJECTED");
        }
        AccessibilityNodeInfo submit = waitForNode(service, SEARCH_BUTTON, deadline);
        if (!click(submit)) throw new MediaPlatformException("UI_ACTION_REJECTED");
        List<Candidate> found = waitForResults(service, query, previousQuery,
                previousCandidates, deadline);
        return new SearchPage(query, version, List.copyOf(found));
    }

    private static AccessibilityNodeInfo waitForSearchSurface(QQMusicAccessibilityService service,
            long deadline) throws MediaPlatformException {
        long settleUntil = deadline;
        while (SystemClock.elapsedRealtime() < settleUntil) {
            AccessibilityNodeInfo root = service.bilibiliRoot();
            if (root != null && (first(root, SEARCH_ENTRY) != null
                    || first(root, SEARCH_INPUT) != null
                    || first(root, SEARCH_FAKE) != null)) return root;
            pause();
        }
        throw new MediaPlatformException("SEARCH_UI_CHANGED");
    }

    private static AccessibilityNodeInfo navigateToSearchSurface(
            QQMusicAccessibilityService service, long deadline) throws MediaPlatformException {
        for (int attempt = 0; attempt < 4 && SystemClock.elapsedRealtime() < deadline;
                attempt++) {
            long window = Math.min(deadline, SystemClock.elapsedRealtime()
                    + (attempt == 0 ? 3_000L : 1_000L));
            try {
                return waitForSearchSurface(service, window);
            } catch (MediaPlatformException missingSearch) {
                if (!"SEARCH_UI_CHANGED".equals(missingSearch.code())) throw missingSearch;
            }
            // Back is issued only while Bilibili owns the active window. Never navigate
            // another app merely because its UI has no search entry.
            if (!service.backWithinBilibili()) break;
        }
        throw new MediaPlatformException("SEARCH_UI_CHANGED");
    }

    private static AccessibilityNodeInfo requireRoot(QQMusicAccessibilityService service,
            long deadline) throws MediaPlatformException {
        while (SystemClock.elapsedRealtime() < deadline) {
            AccessibilityNodeInfo root = service.bilibiliRoot();
            if (root != null) return root;
            pause();
        }
        throw new MediaPlatformException("BILIBILI_NOT_FOREGROUND");
    }

    private static AccessibilityNodeInfo waitForNode(QQMusicAccessibilityService service,
            String id, long deadline) throws MediaPlatformException {
        while (SystemClock.elapsedRealtime() < deadline) {
            AccessibilityNodeInfo root = service.bilibiliRoot();
            AccessibilityNodeInfo node = root == null ? null : first(root, id);
            if (node != null && node.isVisibleToUser()) return node;
            pause();
        }
        throw new MediaPlatformException("SEARCH_UI_CHANGED");
    }

    private static List<Candidate> waitForResults(QQMusicAccessibilityService service,
            String query, String previousQuery, List<Candidate> previousCandidates,
            long deadline) throws MediaPlatformException {
        while (SystemClock.elapsedRealtime() < deadline) {
            AccessibilityNodeInfo root = service.bilibiliRoot();
            if (root != null && query.equals(queryText(root)) && first(root, RESULTS) != null) {
                List<Candidate> found = candidates(root);
                if (!found.isEmpty() && (query.equals(previousQuery)
                        || !found.equals(previousCandidates))) return found;
            }
            pause();
        }
        throw new MediaPlatformException("SEARCH_NO_RESULTS");
    }

    private static List<Candidate> candidates(AccessibilityNodeInfo root) {
        return liveRows(root).stream().map(LiveRow::candidate).toList();
    }

    private static List<LiveRow> liveRows(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo results = first(root, RESULTS);
        if (results == null) return List.of();
        List<LiveRow> rows = new ArrayList<>();
        // On Bilibili 9.12, a nested node's findByViewId can return no descendants
        // even though the same nodes are visible from the page root. Find globally,
        // then constrain each candidate to the visible result container bounds.
        for (AccessibilityNodeInfo titleNode :
                root.findAccessibilityNodeInfosByViewId(OGV_TITLE)) {
            AccessibilityNodeInfo row = ancestorWithId(titleNode, OGV_ROW);
            if (row == null || !within(row, results)
                    || !row.isVisibleToUser() || !row.isClickable()) continue;
            String title = text(titleNode);
            if (title.isEmpty()) continue;
            rows.add(new LiveRow(new Candidate(rows.size() + 1, title,
                    "番剧/影视", "相关作品"), row));
            if (rows.size() >= MAX_CANDIDATES) return rows;
        }
        for (AccessibilityNodeInfo titleNode :
                root.findAccessibilityNodeInfosByViewId(VIDEO_TITLE)) {
            if (!titleNode.isVisibleToUser()) continue;
            String title = text(titleNode);
            AccessibilityNodeInfo row = clickableAncestor(titleNode);
            if (title.isEmpty() || row == null || !within(row, results)) continue;
            String creator = "";
            for (AccessibilityNodeInfo creatorNode :
                    root.findAccessibilityNodeInfosByViewId(VIDEO_CREATOR)) {
                if (isDescendantOf(creatorNode, row)) {
                    creator = text(creatorNode);
                    break;
                }
            }
            rows.add(new LiveRow(new Candidate(rows.size() + 1, title,
                    "视频", creator.isEmpty() ? "UP 主未显示" : "UP 主：" + creator), row));
            if (rows.size() >= MAX_CANDIDATES) break;
        }
        return rows;
    }

    private static AccessibilityNodeInfo ancestorWithId(AccessibilityNodeInfo node, String id) {
        for (int depth = 0; node != null && depth < 20; depth++, node = node.getParent()) {
            if (id.equals(node.getViewIdResourceName())) return node;
        }
        return null;
    }

    private static boolean isDescendantOf(AccessibilityNodeInfo node,
            AccessibilityNodeInfo ancestor) {
        for (int depth = 0; node != null && depth < 20; depth++, node = node.getParent()) {
            if (node.equals(ancestor)) return true;
        }
        return false;
    }

    private static boolean within(AccessibilityNodeInfo child, AccessibilityNodeInfo container) {
        Rect item = new Rect();
        Rect area = new Rect();
        child.getBoundsInScreen(item);
        container.getBoundsInScreen(area);
        return BilibiliResultRules.within(new BilibiliResultRules.Bounds(
                item.left, item.top, item.right, item.bottom),
                new BilibiliResultRules.Bounds(area.left, area.top, area.right, area.bottom));
    }

    private static String queryText(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo fake = first(root, SEARCH_FAKE);
        return fake == null ? "" : text(fake);
    }

    private static AccessibilityNodeInfo first(AccessibilityNodeInfo root, String id) {
        if (root == null) return null;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
        return nodes.isEmpty() ? null : nodes.get(0);
    }

    private static AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo node) {
        for (int depth = 0; node != null && depth < 4; depth++, node = node.getParent()) {
            if (node.isClickable() && node.isVisibleToUser()) return node;
        }
        return null;
    }

    private static boolean click(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo clickable = clickableAncestor(node);
        return clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private static String text(AccessibilityNodeInfo node) {
        return node == null || node.getText() == null ? "" : node.getText().toString().strip();
    }

    private void lockUntil(long deadline) throws MediaPlatformException {
        try {
            long remaining = Math.max(0L, deadline - SystemClock.elapsedRealtime());
            if (!lock.tryLock(remaining, TimeUnit.MILLISECONDS)) {
                throw new MediaPlatformException("UI_BUSY");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new MediaPlatformException("CANCELLED");
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

    private static void pause() throws MediaPlatformException {
        if (Thread.currentThread().isInterrupted()) {
            throw new MediaPlatformException("CANCELLED");
        }
        SystemClock.sleep(POLL_MILLIS);
    }
}
