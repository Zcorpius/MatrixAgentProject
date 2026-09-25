package com.matrix.agent.platform.media;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

/** Debug-only view probe; logs aggregate counts and may submit a search, never starts playback. */
public final class BilibiliViewProbeReceiver extends BroadcastReceiver {
    private static final String TAG = "MatrixBiliViewProbe";

    @Override public void onReceive(Context context, Intent intent) {
        QQMusicAccessibilityService service = QQMusicAccessibilityService.connected();
        AccessibilityNodeInfo root = service == null ? null : service.getRootInActiveWindow();
        if (root == null) {
            Log.i(TAG, "connected=" + (service != null) + " root=null");
            return;
        }
        String query = intent.getStringExtra("query");
        if (query != null && "tv.danmaku.bili".contentEquals(root.getPackageName())) {
            AccessibilityNodeInfo input = first(root, "tv.danmaku.bili:id/search_src_text");
            AccessibilityNodeInfo submit = first(root, "tv.danmaku.bili:id/action_search");
            if (input == null || submit == null || !input.isEditable()
                    || !submit.isClickable()) {
                Log.i(TAG, "queryForm=unavailable");
                return;
            }
            Bundle arguments = new Bundle();
            arguments.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query);
            boolean set = input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
            boolean clicked = set && submit.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            Log.i(TAG, "querySet=" + set + " searchClicked=" + clicked);
            return;
        }
        int[] counts = new int[4];
        visit(root, counts, 0);
        Log.i(TAG, "package=" + root.getPackageName() + " nodes=" + counts[0]
                + " ids=" + counts[1] + " clickable=" + counts[2]
                + " editable=" + counts[3]);
    }

    private static AccessibilityNodeInfo first(AccessibilityNodeInfo root, String id) {
        java.util.List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(id);
        return found.isEmpty() ? null : found.get(0);
    }

    private static void visit(AccessibilityNodeInfo node, int[] counts, int depth) {
        if (node == null || depth > 40 || counts[0] >= 1_000) return;
        counts[0]++;
        if (node.getViewIdResourceName() != null) counts[1]++;
        if (node.isClickable()) counts[2]++;
        if (node.isEditable()) counts[3]++;
        for (int index = 0; index < node.getChildCount(); index++) {
            visit(node.getChild(index), counts, depth + 1);
        }
    }
}
