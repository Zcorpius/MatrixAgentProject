package com.matrix.agent.voice.platform;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.matrix.agent.R;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 按需 microphone 前台服务（设计文档 §7.3）。
 *
 * <p>Host 在创建 AudioRecord 之前必须已进入 microphone 前台类型；
 * 最后一个采音 lease 释放后按平台规则停止。
 * 现有 IVoiceService 的"会话期间按需升级 microphone 前台服务"承诺由此首次真正落地。</p>
 *
 * <p>引用计数：{@link #acquire(String)} / {@link #release(String)} 配对；
 * 归零即 stopForeground+stopSelf。启动被拒（BackgroundStartNotAllowed 等）
 * 返回 false，调用方不得创建 AudioRecord。</p>
 */
public final class VoiceCaptureForegroundService extends Service {

    private static final String TAG = "MatrixAgent";
    private static final String CHANNEL_ID = "voice_capture";
    private static final int NOTIFICATION_ID = 2001;

    private static final AtomicInteger refCount = new AtomicInteger(0);
    private static volatile boolean channelCreated = false;

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        createChannel();
        Notification notification = buildNotification();
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            Log.i(TAG, "[VoiceFGS] microphone 前台服务已启动 ref=" + refCount.get());
        } catch (Exception e) {
            Log.e(TAG, "[VoiceFGS] startForeground 失败（权限被拒或系统限制）", e);
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        refCount.set(0);
        Log.i(TAG, "[VoiceFGS] microphone 前台服务已停止");
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 引用计数 API

    /**
     * 获取采音前台（在创建 AudioRecord 之前调用）。
     * 引用计数 +1 并确保 Service 已启动。
     *
     * @return true = 前台就绪，可以创建 AudioRecord；false = 启动被拒。
     */
    public static boolean acquire(Context context, String owner) {
        int count = refCount.incrementAndGet();
        Log.i(TAG, "[VoiceFGS] acquire owner=" + owner + " ref=" + count);
        if (count == 1) {
            return startService(context);
        }
        return true; // 已在运行
    }

    /** 释放采音前台。归零时停止 Service。 */
    public static void release(Context context, String owner) {
        int count = refCount.decrementAndGet();
        Log.i(TAG, "[VoiceFGS] release owner=" + owner + " ref=" + count);
        if (count <= 0) {
            refCount.set(0);
            try {
                context.stopService(new Intent(context, VoiceCaptureForegroundService.class));
            } catch (Exception e) {
                Log.w(TAG, "[VoiceFGS] stopService 异常", e);
            }
        }
    }

    public static boolean isRunning() {
        return refCount.get() > 0;
    }

    // ---------------------------------------------------------------- 内部

    private static boolean startService(Context context) {
        try {
            Intent intent = new Intent(context, VoiceCaptureForegroundService.class);
            context.startForegroundService(intent);
            return true;
        } catch (Exception e) {
            // SecurityException / IllegalStateException（后台启动限制）
            Log.e(TAG, "[VoiceFGS] 启动失败——不得创建 AudioRecord", e);
            refCount.decrementAndGet();
            return false;
        }
    }

    private void createChannel() {
        if (channelCreated) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "语音采音", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("使用麦克风进行语音识别");
            nm.createNotificationChannel(channel);
        }
        channelCreated = true;
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("MatrixAgent 语音")
                .setContentText("正在监听语音指令")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }
}
