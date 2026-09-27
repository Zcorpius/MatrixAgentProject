package com.matrix.agent.schedule.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.os.SystemClock;
import com.matrix.agent.schedule.ScheduleRuntimeProvider;

/** No full Host graph or external operation is assembled on the broadcast thread. */
public final class ScheduleWakeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !(context.getApplicationContext() instanceof ScheduleRuntimeProvider provider)) return;
        long receivedAt = System.currentTimeMillis(), elapsed = SystemClock.elapsedRealtime();
        PendingResult pending = goAsync();
        PowerManager.WakeLock lease = context.getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MatrixAgent:schedule-admission");
        lease.setReferenceCounted(false); lease.acquire(5_000);
        provider.scheduleRuntime().wake(intent.getAction(), intent.getLongExtra(ScheduleAlarmAdapter.EXTRA_EPOCH, -1),
                receivedAt, elapsed, success -> {
                    if (lease.isHeld()) lease.release();
                    pending.finish();
                });
    }
}
