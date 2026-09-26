package com.matrix.agent.platform.media;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

/** User-enabled media UI service; component name is retained to preserve existing enablement. */
public final class QQMusicAccessibilityService extends AccessibilityService {
    private static volatile QQMusicAccessibilityService connected;

    static QQMusicAccessibilityService connected() { return connected; }

    @Override protected void onServiceConnected() { connected = this; }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // No global event collection. Tools obtain a fresh, package-checked tree on demand.
    }

    @Override public void onInterrupt() {}

    @Override public void onDestroy() {
        if (connected == this) connected = null;
        super.onDestroy();
    }

    AccessibilityNodeInfo qqRoot() {
        return rootFor(MediaApp.QQMUSIC);
    }

    AccessibilityNodeInfo bilibiliRoot() {
        return rootFor(MediaApp.BILIBILI);
    }

    boolean backWithinBilibili() {
        AccessibilityNodeInfo root = bilibiliRoot();
        if (root == null) return false;
        java.util.List<AccessibilityNodeInfo> backNodes = java.util.List.of();
        try {
            backNodes = root.findAccessibilityNodeInfosByText("返回");
            for (AccessibilityNodeInfo node : backNodes) {
                if (node.isVisibleToUser() && node.isClickable()
                        && ("返回".contentEquals(node.getText()) || "返回".contentEquals(node.getContentDescription()))
                        && MediaApp.BILIBILI.packageName().contentEquals(node.getPackageName())
                        && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            }
        } finally {
            backNodes.forEach(AccessibilityNodeInfo::recycle);
            root.recycle();
        }
        return targetOwnsInteraction(MediaApp.BILIBILI, true) && performGlobalAction(GLOBAL_ACTION_BACK);
    }

    /** Targeted IME actions require target focus; global Back additionally rejects an open IME. */
    boolean canUseFocusedInput(MediaApp app) { return targetOwnsInteraction(app, false); }

    private boolean targetOwnsInteraction(MediaApp app, boolean rejectIme) {
        var windows = getWindows();
        try {
            for (var window : windows) {
                if (window.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    if (rejectIme) return false;
                    continue;
                }
                if (window.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) return false;
                if (window.isFocused() || window.isActive()) {
                    AccessibilityNodeInfo root = window.getRoot();
                    try {
                        if (root == null || !app.packageName().contentEquals(root.getPackageName())) return false;
                    } finally { if (root != null) root.recycle(); }
                }
            }
        } finally { windows.forEach(AccessibilityWindowInfo::recycle); }
        AccessibilityNodeInfo active = getRootInActiveWindow();
        try { return active != null && app.packageName().contentEquals(active.getPackageName()); }
        finally { if (active != null) active.recycle(); }
    }

    private AccessibilityNodeInfo rootFor(MediaApp app) {
        var windows = getWindows();
        try {
            for (var window : windows) {
                if (window.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
                        || (android.os.Build.VERSION.SDK_INT >= 30 && window.getDisplayId() != 0)) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                android.graphics.Rect bounds = new android.graphics.Rect();
                window.getBoundsInScreen(bounds);
                if (!bounds.isEmpty() && app.packageName().contentEquals(root.getPackageName())) return root;
                root.recycle();
            }
        } finally { windows.forEach(AccessibilityWindowInfo::recycle); }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && app.packageName().contentEquals(root.getPackageName())) return root;
        if (root != null) root.recycle();
        return null;
    }
}
