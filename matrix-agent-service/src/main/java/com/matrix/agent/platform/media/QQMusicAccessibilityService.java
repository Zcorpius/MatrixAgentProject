package com.matrix.agent.platform.media;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

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
        return bilibiliRoot() != null && performGlobalAction(GLOBAL_ACTION_BACK);
    }

    private AccessibilityNodeInfo rootFor(MediaApp app) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        return root != null && root.getPackageName() != null
                && app.packageName().contentEquals(root.getPackageName())
                ? root : null;
    }
}
