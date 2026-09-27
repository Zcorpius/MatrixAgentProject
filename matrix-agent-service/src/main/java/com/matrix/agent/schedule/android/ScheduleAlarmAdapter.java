package com.matrix.agent.schedule.android;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import com.matrix.agent.data.schedule.ScheduleArmEntity;

/** Called only by the schedule lane: one stable physical registration across all plans. */
public final class ScheduleAlarmAdapter {
    public static final String ACTION_DUE = "com.matrix.agent.schedule.DUE";
    public static final String EXTRA_EPOCH = "dataEpoch";
    private final Context context;
    private final AlarmManager alarms;
    public ScheduleAlarmAdapter(Context context) {
        this.context = context.getApplicationContext();
        alarms = context.getSystemService(AlarmManager.class);
    }
    public boolean allowed() { return alarms != null && (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()); }
    public String apply(ScheduleArmEntity arm) {
        PendingIntent operation = operation(arm.dataEpoch, arm.armGeneration);
        if (arm.desiredDueAt == null) { alarms.cancel(operation); return ""; }
        if (!allowed()) { alarms.cancel(operation); return "EXACT_ALARM_PERMISSION_DENIED"; }
        try {
            alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, arm.elapsedDueAt, operation);
            return "";
        } catch (SecurityException denied) { return "EXACT_ALARM_PERMISSION_DENIED"; }
    }
    public void cancel() { if (alarms != null) alarms.cancel(operation(-1, -1)); }
    private PendingIntent operation(long epoch, long generation) {
        Intent intent = new Intent(context, ScheduleWakeReceiver.class).setAction(ACTION_DUE)
                .setData(Uri.parse("matrix-schedule://user/0/arm"))
                .putExtra(EXTRA_EPOCH, epoch).putExtra("armGeneration", generation);
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
