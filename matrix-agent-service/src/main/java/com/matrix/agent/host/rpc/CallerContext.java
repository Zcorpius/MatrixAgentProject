package com.matrix.agent.host.rpc;
import com.matrix.agent.host.MatrixAgentManagerService;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Process;

import androidx.annotation.NonNull;

import java.util.Arrays;

/** Immutable caller identity captured once at the Binder boundary. */
public final class CallerContext {
    /** Android's stable multi-user uid layout; public UserHandle.getUserId is hidden to apps. */
    private static final int PER_USER_RANGE = 100_000;
    public final int uid;
    public final int userId;
    @NonNull public final String packageName;

    private CallerContext(int uid, int userId, @NonNull String packageName) {
        this.uid = uid;
        this.userId = userId;
        this.packageName = packageName;
    }

    @NonNull
    public static CallerContext capture(@NonNull Context context) {
        int uid = Binder.getCallingUid();
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        String packageName = packages == null || packages.length == 0
                ? "" : Arrays.stream(packages).sorted().findFirst().orElse("");
        return new CallerContext(uid, uid / PER_USER_RANGE, packageName);
    }

    /**
     * Enforces both the signature permission and the serving package's signing identity.  The
     * latter prevents an accidentally normal-protection permission from becoming an authority
     * boundary. system/root is reserved for platform integration only.
     */
    public void enforceTrusted(@NonNull Context context) {
        if (uid == Process.SYSTEM_UID || uid == Process.ROOT_UID) return;
        if (context.checkCallingOrSelfPermission(MatrixAgentManagerService.ACCESS_PERMISSION)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("matrix agent access permission denied");
        }
        if (packageName.isEmpty() || context.getPackageManager().checkSignatures(
                context.getPackageName(), packageName) != PackageManager.SIGNATURE_MATCH) {
            throw new SecurityException("untrusted caller uid=" + uid);
        }
    }
}
