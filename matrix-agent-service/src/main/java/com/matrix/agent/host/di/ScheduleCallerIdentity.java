package com.matrix.agent.host.di;

import android.content.Context;
import android.content.pm.PackageManager;
import com.matrix.agent.host.rpc.CallerContext;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.VehicleZone;
import com.matrix.agent.schedule.domain.ScheduleCodec;
import com.matrix.agent.schedule.domain.ScheduleIdentity;

/** Occupant mapping is a Host policy decision, never supplied by a schedule DTO. */
public final class ScheduleCallerIdentity {
    private ScheduleCallerIdentity() { }
    public static ScheduleIdentity capture(Context context, Actor actor, VehicleZone zone) {
        CallerContext caller = CallerContext.capture(context);
        caller.enforceTrusted(context);
        return new ScheduleIdentity(caller.uid, caller.userId, caller.packageName,
                signature(context, caller.packageName), actor, zone);
    }
    public static String signature(Context context, String packageName) {
        try {
            var info = context.getPackageManager().getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES);
            if (info.signingInfo == null) throw new SecurityException("signing identity unavailable");
            return ScheduleCodec.digest(java.util.Arrays.stream(info.signingInfo.getApkContentsSigners())
                    .map(android.content.pm.Signature::toCharsString).sorted().toArray(String[]::new));
        } catch (PackageManager.NameNotFoundException missing) { throw new SecurityException("owner no longer installed"); }
    }
}
