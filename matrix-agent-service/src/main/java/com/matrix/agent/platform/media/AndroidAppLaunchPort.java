package com.matrix.agent.platform.media;

import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

/** Intent dispatch only; a successful call does not assert that a page became foreground. */
public final class AndroidAppLaunchPort implements AppLaunchPort {
    private static final String BACKGROUND_START_PERMISSION =
            "android.permission.START_ACTIVITIES_FROM_BACKGROUND";

    private final Context context;
    private final ExternalAppHandoffPort handoff;

    public AndroidAppLaunchPort(Context context, ExternalAppHandoffPort handoff) {
        this.context = context.getApplicationContext();
        this.handoff = handoff;
    }

    @Override public void openApp(MediaApp app, LaunchContext ctx) throws MediaPlatformException {
        requireLaunchContext();
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(app.packageName());
        if (intent == null) throw new MediaPlatformException("ACTION_UNSUPPORTED");
        dispatch(intent, app, ctx);
    }

    @Override public void openBilibiliVideo(String bvid, Integer page, LaunchContext ctx)
            throws MediaPlatformException {
        requireLaunchContext();
        String url = "https://www.bilibili.com/video/" + bvid;
        if (page != null) url += "?p=" + page;
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.setPackage(MediaApp.BILIBILI.packageName());
        dispatch(intent, MediaApp.BILIBILI, ctx);
    }

    private void requireLaunchContext() throws MediaPlatformException {
        if (context.checkSelfPermission(BACKGROUND_START_PERMISSION)
                == PackageManager.PERMISSION_GRANTED) return;
        ActivityManager.RunningAppProcessInfo process = new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(process);
        if (process.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) {
            throw new MediaPlatformException("UI_ACTION_REQUIRED");
        }
    }

    private void dispatch(Intent intent, MediaApp app, LaunchContext ctx) throws MediaPlatformException {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (intent.resolveActivity(context.getPackageManager()) == null) {
                throw new MediaPlatformException("ACTION_UNSUPPORTED");
            }
            try {
                handoff.prepare(ctx, app, com.matrix.agent.api.handoff.HandoffProtocol.LAUNCH_ACTIVITY);
                ctx.checkActive();
                requireLaunchContext();
                context.startActivity(intent);
                handoff.launchFinished(ctx, com.matrix.agent.api.handoff.HandoffProtocol.DISPATCHED);
            } catch (MediaPlatformException cancelled) {
                handoff.launchFinished(ctx, com.matrix.agent.api.handoff.HandoffProtocol.DISPATCH_CANCELLED);
                throw cancelled;
            }
        } catch (ActivityNotFoundException | SecurityException failure) {
            handoff.launchFinished(ctx, com.matrix.agent.api.handoff.HandoffProtocol.DISPATCH_FAILED);
            throw new MediaPlatformException("BACKGROUND_LAUNCH_BLOCKED");
        }
    }
}
