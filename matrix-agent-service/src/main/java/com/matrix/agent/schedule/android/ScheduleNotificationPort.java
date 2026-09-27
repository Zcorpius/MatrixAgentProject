package com.matrix.agent.schedule.android;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import com.matrix.agent.api.common.MatrixServiceConstants;
import com.matrix.agent.data.schedule.ScheduleRunEntity;
import com.matrix.agent.schedule.domain.ScheduleCodec;

/** Posting confirms handoff to NotificationManager, never that the user heard or read a reminder. */
public final class ScheduleNotificationPort {
    public static final String REMINDERS = "schedule_reminders", STATUS = "schedule_execution_status", ATTENTION = "schedule_attention";
    private final Context context;
    private final NotificationManager notifications;
    public ScheduleNotificationPort(Context context) {
        this.context = context.getApplicationContext(); notifications = context.getSystemService(NotificationManager.class);
        // Failures remain visible as a blocked channel instead of crashing a cold BroadcastReceiver.
        ensureChannels();
    }
    private String ensureChannels() {
        if (notifications == null) return "NOTIFICATION_SERVICE_UNAVAILABLE";
        try {
        // Idempotent creation preserves importance, sound and DND choices already set by the user.
        notifications.createNotificationChannels(java.util.List.of(
                channel(REMINDERS, "计划提醒", NotificationManager.IMPORTANCE_HIGH),
                channel(STATUS, "计划执行状态", NotificationManager.IMPORTANCE_LOW),
                channel(ATTENTION, "计划需要处理", NotificationManager.IMPORTANCE_DEFAULT)));
            for (String id : java.util.List.of(REMINDERS, STATUS, ATTENTION))
                if (notifications.getNotificationChannel(id) == null) return "NOTIFICATION_CHANNEL_MISSING";
            return "";
        } catch (RuntimeException unavailable) { return "NOTIFICATION_CHANNEL_INITIALIZATION_FAILED"; }
    }
    private static NotificationChannel channel(String id, String title, int importance) {
        NotificationChannel channel = new NotificationChannel(id, title, importance);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        return channel;
    }
    public boolean allowed() { return blockedReason(REMINDERS).isEmpty(); }
    public String blockedReason(String channelId) {
        try { return checkBlocked(channelId); }
        catch (RuntimeException unavailable) { return "NOTIFICATION_SERVICE_UNAVAILABLE"; }
    }
    private String checkBlocked(String channelId) {
        if (notifications == null) return "NOTIFICATION_SERVICE_UNAVAILABLE";
        if (java.util.List.of(REMINDERS, STATUS, ATTENTION).stream().anyMatch(id -> notifications.getNotificationChannel(id) == null)) {
            String failure = ensureChannels(); if (!failure.isEmpty()) return failure;
        }
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return "NOTIFICATION_PERMISSION_DENIED";
        if (!notifications.areNotificationsEnabled()) return "NOTIFICATIONS_DISABLED";
        NotificationChannel channel = notifications.getNotificationChannel(channelId);
        if (channel == null || channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return "NOTIFICATION_CHANNEL_BLOCKED";
        return "";
    }
    public String post(ScheduleRunEntity run) {
        return post(run, ScheduleCodec.spec(run.specJson).action.text, REMINDERS);
    }
    public String postResult(ScheduleRunEntity run, String text) { return post(run, text, resultChannel(run)); }
    public static String resultChannel(ScheduleRunEntity run) {
        boolean attention = run.state == com.matrix.agent.api.schedule.ScheduleCodes.FAILED
                || run.state == com.matrix.agent.api.schedule.ScheduleCodes.PARTIAL
                || run.state == com.matrix.agent.api.schedule.ScheduleCodes.EXECUTION_UNKNOWN;
        return attention ? ATTENTION : STATUS;
    }
    public String policySnapshot(String channel) {
        try {
            var selected = notifications == null ? null : notifications.getNotificationChannel(channel);
            return new org.json.JSONObject().put("channel", channel)
                    .put("importance", selected == null ? -1 : selected.getImportance())
                    .put("interruptionFilter", notifications == null ? 0 : notifications.getCurrentInterruptionFilter())
                    .put("observedAt", System.currentTimeMillis()).toString();
        } catch (RuntimeException | org.json.JSONException unavailable) { return "{}"; }
    }
    private synchronized String post(ScheduleRunEntity run, String text, String channel) {
        String blocked = blockedReason(channel);
        if (!blocked.isEmpty()) return blocked;
        var active = notifications.getActiveNotifications();
        long count = java.util.Arrays.stream(active).filter(item -> item.getTag() != null && item.getTag().startsWith("schedule:")).count();
        boolean grouped = count >= 8 || ATTENTION.equals(channel);
        String tag = grouped ? "schedule:summary:" + channel : "schedule:" + run.runId;
        // A batch has one visible summary, while every occurrence remains separately queryable in history.
        if (grouped && java.util.Arrays.stream(active).anyMatch(item -> tag.equals(item.getTag()) && System.currentTimeMillis() - item.getPostTime() < 30_000)) return "";
        Intent open = new Intent(MatrixServiceConstants.ACTION_OPEN_SCHEDULE)
                .setComponent(new ComponentName("com.matrix.agent.launcher", "com.matrix.agent.launcher.LauncherActivity"))
                .setData(Uri.parse("matrix-schedule://runs/" + run.runId))
                .putExtra(MatrixServiceConstants.EXTRA_SCHEDULE_ID, run.scheduleId)
                .putExtra(MatrixServiceConstants.EXTRA_SCHEDULE_RUN_ID, run.runId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (grouped) {
            open.removeExtra(MatrixServiceConstants.EXTRA_SCHEDULE_RUN_ID);
            open.removeExtra(MatrixServiceConstants.EXTRA_SCHEDULE_ID);
            open.setData(Uri.parse("matrix-schedule://history"));
        }
        String shownTitle = grouped ? ATTENTION.equals(channel) ? "计划需要处理" : "多项计划已到期" : run.title;
        String shownText = grouped ? "打开任务中心查看逐项提醒内容、时间与执行结果。" : text;
        PendingIntent content = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(context, channel)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle(shownTitle)
                .setContentText(shownText).setStyle(new Notification.BigTextStyle().bigText(shownText))
                .setCategory(Notification.CATEGORY_REMINDER).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setContentIntent(content).setAutoCancel(true).setOnlyAlertOnce(true)
                .setGroup("matrix-schedules:" + channel).setGroupSummary(grouped)
                .setWhen(run.scheduledAt).setShowWhen(true).build();
        notifications.notify(tag, 1, notification);
        // notify() is asynchronous; verify that the system retained the notification rather than assuming success.
        for (int attempt = 0; attempt < 10; attempt++) {
            if (java.util.Arrays.stream(notifications.getActiveNotifications()).anyMatch(item -> tag.equals(item.getTag()))) return "";
            android.os.SystemClock.sleep(10);
        }
        return "NOTIFICATION_NOT_CONFIRMED";
    }
    public void clear() {
        if (notifications == null) return;
        for (android.service.notification.StatusBarNotification notification : notifications.getActiveNotifications()) {
            if (notification.getTag() != null && notification.getTag().startsWith("schedule:")) notifications.cancel(notification.getTag(), notification.getId());
        }
    }
}
