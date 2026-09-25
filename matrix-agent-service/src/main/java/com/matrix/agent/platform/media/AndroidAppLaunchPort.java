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

    public AndroidAppLaunchPort(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override public void openApp(MediaApp app) throws MediaPlatformException {
        requireLaunchContext();
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(app.packageName());
        if (intent == null) throw new MediaPlatformException("ACTION_UNSUPPORTED");
        dispatch(intent);
    }

    @Override public void openBilibiliVideo(String bvid, Integer page)
            throws MediaPlatformException {
        requireLaunchContext();
        String url = "https://www.bilibili.com/video/" + bvid;
        if (page != null) url += "?p=" + page;
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        intent.setPackage(MediaApp.BILIBILI.packageName());
        dispatch(intent);
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

    private void dispatch(Intent intent) throws MediaPlatformException {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (intent.resolveActivity(context.getPackageManager()) == null) {
                throw new MediaPlatformException("ACTION_UNSUPPORTED");
            }
            context.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException failure) {
            throw new MediaPlatformException("BACKGROUND_LAUNCH_BLOCKED");
        }
    }
}
