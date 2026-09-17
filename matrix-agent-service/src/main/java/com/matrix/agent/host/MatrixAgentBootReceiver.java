package com.matrix.agent.host;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Starts the system-UID manager after boot and after an in-place system APK update. */
public final class MatrixAgentBootReceiver extends BroadcastReceiver {
    private static final String TAG = "MatrixAgentBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        try {
            context.startService(new Intent(context, MatrixAgentManagerService.class));
        } catch (RuntimeException e) {
            // Keep the receiver short-lived. The failure is visible in logcat and an explicit
            // signature-protected bind can still start the service once the system is ready.
            Log.e(TAG, "Unable to start Matrix manager for " + action, e);
        }
    }
}
