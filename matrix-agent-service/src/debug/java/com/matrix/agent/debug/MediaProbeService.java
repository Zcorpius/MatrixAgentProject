package com.matrix.agent.debug;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.matrix.agent.identity.CancellationToken;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Debug-only lifecycle owner for privileged probes; never bundled into release. */
public final class MediaProbeService extends Service {
    private static final String TAG = "MatrixMediaProbe";
    private static final String CHANNEL = "matrix_debug_probes";
    private static final int NOTIFICATION_ID = 9701;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicReference<CancellationToken> active = new AtomicReference<>();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(8), runnable -> new Thread(runnable, "matrix-media-probe"));
    // Accessed only on the main thread, including completion callbacks.
    private int pendingJobs;
    private int latestStartId;
    private volatile boolean destroyed;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL, "调试验证", NotificationManager.IMPORTANCE_LOW));
        startForeground(NOTIFICATION_ID, new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Matrix Agent 调试验证")
                .setContentText("正在运行真机探针")
                .setOngoing(true).build());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        latestStartId = startId;
        if (intent == null) {
            stopIfIdle();
            return START_NOT_STICKY;
        }
        Intent command = new Intent(intent);
        pendingJobs++;
        try {
            worker.execute(() -> {
                CancellationToken token = new CancellationToken();
                active.set(token);
                Log.i(TAG, "probe started id=" + startId);
                try {
                    if (!destroyed && !Thread.currentThread().isInterrupted()) {
                        MediaProbeReceiver.run(getApplicationContext(), command, token);
                    }
                } catch (RuntimeException failure) {
                    Log.e(TAG, "probe failed id=" + startId + " cause=" + failure.getClass().getSimpleName());
                } finally {
                    token.cancel();
                    active.compareAndSet(token, null);
                    Log.i(TAG, "probe finished id=" + startId);
                    main.post(() -> {
                        pendingJobs--;
                        stopIfIdle();
                    });
                }
            });
        } catch (RejectedExecutionException overloaded) {
            pendingJobs--;
            Log.w(TAG, "probe rejected id=" + startId + " cause=QUEUE_FULL");
            stopIfIdle();
        }
        return START_NOT_STICKY;
    }

    private void stopIfIdle() {
        if (!destroyed && pendingJobs == 0) stopSelfResult(latestStartId);
    }

    @Override public void onDestroy() {
        destroyed = true;
        CancellationToken token = active.getAndSet(null);
        if (token != null) token.cancel();
        worker.shutdownNow();
        main.removeCallbacksAndMessages(null);
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
