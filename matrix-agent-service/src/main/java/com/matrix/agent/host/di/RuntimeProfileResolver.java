package com.matrix.agent.host.di;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

import com.matrix.agent.identity.RuntimeProfile;
import com.matrix.agent.identity.RuntimeProfileSource;

/**
 * Resolves a profile from system features and platform-signed deployment metadata.
 * A contradictory or unrecognised deployment setting fails closed.
 */
public final class RuntimeProfileResolver implements RuntimeProfileSource {
    private static final String TAG = "MatrixAgent";
    private static final String META_PROFILE = "com.matrix.agent.runtime_profile";
    private final RuntimeProfile profile;

    public RuntimeProfileResolver(Context context) {
        PackageManager packages = context.getPackageManager();
        RuntimeProfile resolved = RuntimeProfile.UNKNOWN;
        try {
            String configured = deploymentSetting(context, packages);
            boolean automotive = packages.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE);
            if ("AUTO".equals(configured)
                    || ("PHONE".equals(configured) && !automotive)
                    || ("AUTOMOTIVE".equals(configured) && automotive)) {
                resolved = automotive ? RuntimeProfile.AUTOMOTIVE : RuntimeProfile.PHONE;
            }
        } catch (RuntimeException | PackageManager.NameNotFoundException error) {
            Log.w(TAG, "[Profile] resolution failed: " + error.getClass().getSimpleName());
        }
        profile = resolved;
        Log.i(TAG, "[Profile] runtime=" + profile);
    }

    private static String deploymentSetting(Context context, PackageManager packages)
            throws PackageManager.NameNotFoundException {
        ApplicationInfo info = packages.getApplicationInfo(context.getPackageName(),
                PackageManager.GET_META_DATA);
        Bundle data = info.metaData;
        return data == null ? null : data.getString(META_PROFILE);
    }

    @Override public RuntimeProfile snapshot() { return profile; }
}
