package com.matrix.agent.platform.media;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

public final class AndroidPackageProbe implements PackageProbe {
    private final PackageManager packages;

    public AndroidPackageProbe(Context context) {
        packages = context.getPackageManager();
    }

    @Override public boolean installedAndEnabled(MediaApp app) {
        try {
            ApplicationInfo info = packages.getApplicationInfo(app.packageName(), 0);
            return info.enabled;
        } catch (PackageManager.NameNotFoundException | SecurityException missing) {
            return false;
        }
    }
}
