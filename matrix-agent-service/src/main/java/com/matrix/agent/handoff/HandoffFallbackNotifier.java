package com.matrix.agent.handoff;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import com.matrix.agent.api.common.MatrixServiceConstants;
import com.matrix.agent.api.handoff.HandoffProtocol;
import java.util.LinkedHashSet;
import java.util.Set;

/** Host-owned, content-free return entry. A notification never restarts the external action. */
public final class HandoffFallbackNotifier implements HandoffCoordinator.Fallback {
    private static final String CHANNEL = "agent_external_task";
    private final Context context;
    private final Set<String> notified = new LinkedHashSet<>();
    public HandoffFallbackNotifier(Context context) { this.context = context.getApplicationContext(); }
    @Override public synchronized void show(HandoffContextRegistry.Binding binding, int result) {
        if (!com.matrix.agent.identity.ActorUsers.USER_DRIVER.equals(binding.ownerUserId())
                || !"DRIVER".equals(binding.zone())) {
            Log.w("MatrixHandoff", "notification unavailable: conversation domain"); return;
        }
        if (notified.contains(binding.runtimeRequestId())) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || !manager.areNotificationsEnabled()) {
            Log.w("MatrixHandoff", "notification unavailable: permission"); return;
        }
        Intent intent = new Intent(HandoffProtocol.ACTION_OPEN_CONVERSATION)
                .setClassName(MatrixServiceConstants.LAUNCHER_PACKAGE,
                        "com.matrix.agent.launcher.LauncherActivity")
                .setData(android.net.Uri.parse("matrix-agent://conversation/" + binding.conversationId()))
                .putExtra(HandoffProtocol.EXTRA_CONVERSATION_ID, binding.conversationId())
                .putExtra(HandoffProtocol.EXTRA_MESSAGE_ID, binding.hostUserMessageId())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (intent.resolveActivity(context.getPackageManager()) == null) {
            Log.w("MatrixHandoff", "notification unavailable: launcher missing"); return;
        }
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "跨应用任务",
                NotificationManager.IMPORTANCE_LOW));
        if (manager.getNotificationChannel(CHANNEL).getImportance() == NotificationManager.IMPORTANCE_NONE) {
            Log.w("MatrixHandoff", "notification unavailable: channel disabled"); return;
        }
        try {
            PendingIntent target = PendingIntent.getActivity(context, binding.runtimeRequestId().hashCode(),
                    intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = new Notification.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Agent 任务")
                    .setContentText("点按返回会话，查看任务进度和结果")
                    .setVisibility(Notification.VISIBILITY_PRIVATE).setContentIntent(target)
                    .setAutoCancel(true).setOnlyAlertOnce(true).build();
            manager.notify(binding.runtimeRequestId(), 1, notification);
            notified.add(binding.runtimeRequestId());
            if (notified.size() > 128) notified.remove(notified.iterator().next());
        } catch (RuntimeException failure) { Log.w("MatrixHandoff", "notification unavailable", failure); }
    }
}
