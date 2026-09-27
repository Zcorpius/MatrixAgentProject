package com.matrix.agent.schedule.android;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import com.matrix.agent.schedule.execution.ScheduleExecutionRuntime;

/** Foreground lifetime exists only while explicitly authored automatic work is active. */
public final class ScheduleExecutionService extends Service {
    private final Handler main = new Handler(Looper.getMainLooper());
    private int leases;
    private android.os.PowerManager.WakeLock startup;
    private ScheduleExecutionRuntime runtime;
    @Override public void onCreate() {
        super.onCreate();
        startup = getSystemService(android.os.PowerManager.class).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "MatrixAgent:schedule-service-start");
        startup.setReferenceCounted(false); startup.acquire(30_000);
        new ScheduleNotificationPort(this); // Channels must exist before the foreground notification.
        startForeground(7301, notification(""));
        runtime = ((ScheduleExecutionRuntime.Provider) getApplication()).scheduleExecutionRuntime();
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String id = intent == null ? null : intent.getStringExtra("runId");
        if (id == null) { stopSelf(startId); return START_NOT_STICKY; }
        leases++;
        getSystemService(android.app.NotificationManager.class).notify(7301, notification(leases == 1 ? id : ""));
        runtime.execute(id, () -> main.post(() -> { if (--leases == 0) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); } }));
        return START_NOT_STICKY;
    }
    private Notification notification(String runId) {
        boolean run = !runId.isEmpty() && !runId.startsWith("calendar:");
        Intent open = new Intent(com.matrix.agent.api.common.MatrixServiceConstants.ACTION_OPEN_SCHEDULE)
                .setClassName("com.matrix.agent.launcher", "com.matrix.agent.launcher.LauncherActivity")
                .setData(android.net.Uri.parse(run ? "matrix-schedule://runs/" + runId : "matrix-schedule://history"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (run) open.putExtra(com.matrix.agent.api.common.MatrixServiceConstants.EXTRA_SCHEDULE_RUN_ID, runId);
        var pending = android.app.PendingIntent.getActivity(this, 7301, open,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, ScheduleNotificationPort.STATUS)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("计划正在执行")
                .setContentText("打开任务中心查看进度或停止本次运行")
                .setContentIntent(pending)
                .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_view),
                        "查看或停止", pending).build())
                .setCategory(Notification.CATEGORY_SERVICE).setOngoing(true).setOnlyAlertOnce(true).build();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        if (startup != null && startup.isHeld()) startup.release();
        if (leases > 0 && runtime != null) runtime.interruptAll();
        super.onDestroy();
    }
}
