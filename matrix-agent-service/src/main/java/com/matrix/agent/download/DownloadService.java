package com.matrix.agent.download;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.matrix.agent.data.db.ModelDownloadDao;
import com.matrix.agent.data.db.ModelDownloadEntity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 端侧模型下载前台服务——在专用线程池跑 {@link ModelDownloadManager#download}，通知栏显示
 * 进度文字（百分比）。下载完成/失败后 {@link #stopSelf}。
 *
 * <p><b>线程模型</b>：阻塞下载跑在 Host 全局 network/download 预算
 * ({@link MatrixExecutorRegistry#networkExecutor()}，4 线程/16 队列)；进度观察复用 Host
 * 有界 timer，不以 {@code sleep} 长时间占用网络 worker。本 Service 不自建线程（审计 A-113）。
 *
 * <p><b>foregroundServiceType</b>：manifest 已声明 {@code dataSync}，targetSdk 36 要求
 * startForeground 显式传 {@link ServiceInfo#FOREGROUND_SERVICE_TYPE_DATA_SYNC}（API 30+ 走
 * 3 参重载，API 28/29 走 2 参重载，类型由 manifest 提供）。
 */
public final class DownloadService extends Service {
    private static final String TAG = "MatrixAgent";

    public static final String ACTION_DOWNLOAD = "com.matrix.agent.action.DOWNLOAD";
    public static final String EXTRA_MODEL_NAME = "model_name";
    public static final String EXTRA_MODEL_SCOPE_REPO = "model_scope_repo";
    public static final String EXTRA_DESCRIPTION = "description";
    public static final String EXTRA_SIZE_GB = "size_gb";

    static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "model_download";
    private static final long POLL_INTERVAL_MS = 800L;

    /**
     * A service instance can receive multiple start commands.  A boolean plus a model name is
     * insufficient here: an old worker can finish after a newer start and accidentally mark the
     * newer run inactive.  Every start therefore owns a distinct run token and only the current
     * token is allowed to update service lifetime or notifications.
     */
    private final Object runLock = new Object();
    private final AtomicLong nextRunId = new AtomicLong();
    @Nullable private volatile DownloadRun currentRun;

    /** Shared entry point for the Binder and its constraint-aware WorkManager admission worker. */
    public static void start(@NonNull Context context, @NonNull ModelMarketClient.ModelEntry entry) {
        Intent intent = new Intent(context, DownloadService.class)
                .setAction(ACTION_DOWNLOAD)
                .putExtra(EXTRA_MODEL_NAME, entry.modelName)
                .putExtra(EXTRA_MODEL_SCOPE_REPO, entry.modelScopeRepo)
                .putExtra(EXTRA_DESCRIPTION, entry.description)
                .putExtra(EXTRA_SIZE_GB, entry.sizeGb);
        ContextCompat.startForegroundService(context, intent);
    }

    @Nullable
    @Override
    public IBinder onBind(@Nullable Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_DOWNLOAD.equals(intent.getAction())) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        String modelName = intent.getStringExtra(EXTRA_MODEL_NAME);
        String repo = intent.getStringExtra(EXTRA_MODEL_SCOPE_REPO);
        if (modelName == null || modelName.isEmpty() || repo == null || repo.isEmpty()) {
            Log.w(TAG, "[DownloadService] missing modelName/repo, stop");
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        DownloadRuntime runtime = downloadRuntime();
        ModelDownloadManager manager = runtime != null ? runtime.modelDownloadManager() : null;
        ModelDownloadDao dao = runtime != null ? runtime.modelDownloadDao() : null;
        if (manager == null || dao == null) {
            // database=null（SQLCipher/KeyStore 不可用）的降级路径——下载功能依赖 DAO 落进度。
            Log.w(TAG, "[DownloadService] manager/dao unavailable (DB degraded), stop");
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        String desc = intent.getStringExtra(EXTRA_DESCRIPTION);
        double sizeGb = intent.getDoubleExtra(EXTRA_SIZE_GB, 0d);
        ModelMarketClient.ModelEntry entry = new ModelMarketClient.ModelEntry(
                modelName, desc != null ? desc : modelName, sizeGb, repo);

        DownloadRun run = replaceRun(modelName, startId, manager);
        try {
            startForegroundCompat(buildNotification(modelName, 0,
                    getString(com.matrix.agent.R.string.download_preparing)));
        } catch (Exception fgError) {
            // 前台服务被拒（Android 12+ StartNotAllowed / 通知权限受限）时不得转入
            // 隐形后台下载：分钟级任务违反后台执行限制且状态与用户认知脱节。
            // 转为可恢复失败——终止本次下载（保留 .tmp 断点与 DAO 状态）并结束服务。
            Log.w(TAG, "[DownloadService] startForeground denied: " + fgError.getMessage());
            try { manager.cancel(modelName); } catch (Exception ignored) { }
            finishRun(run);
            return START_NOT_STICKY;
        }

        ExecutorService pool = workExecutor();
        try {
            scheduleProgressPoll(run, dao);
            pool.execute(() -> runDownload(run, entry, manager));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // network 池饱和（4 线程/16 队列满）：不向 Service 主线程冒泡崩溃，
            // 转为可重试失败——终止本次提交并 stopSelf，用户可稍后重试。
            Log.w(TAG, "[DownloadService] download pool saturated: " + e.getMessage());
            try { manager.cancel(modelName); } catch (Exception ignored) { }
            finishRun(run);
            return START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }

    /** Bounded timer polling; every run owns and cancels its own future. */
    private void scheduleProgressPoll(@NonNull DownloadRun run, ModelDownloadDao dao) {
        ScheduledFuture<?> future = progressScheduler().scheduleWithFixedDelay(() -> {
            if (!isCurrent(run)) {
                cancelProgressPoll(run);
                return;
            }
            try {
                ModelDownloadEntity e = dao.getByName(run.modelName);
                if (e == null) return;
                int pct = computePct(e);
                notifyProgress(run.modelName, pct, subTextForStatus(e.status, run.modelName, pct));
                if (isTerminal(e.status)) cancelProgressPoll(run);
            } catch (Exception e) {
                Log.w(TAG, "[DownloadService] poll error: " + e.getMessage());
            }
        }, 0L, POLL_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        ScheduledFuture<?> previous = run.progressPoll.getAndSet(future);
        if (previous != null) previous.cancel(false);
        if (!isCurrent(run)) cancelProgressPoll(run);
    }

    /** 实际下载任务——阻塞调 manager.download，结束后置终态通知 + stopSelf。 */
    private void runDownload(@NonNull DownloadRun run, ModelMarketClient.ModelEntry entry,
            ModelDownloadManager manager) {
        try {
            manager.download(entry);
        } catch (Exception e) {
            Log.e(TAG, "[DownloadService] download failed: " + entry.modelName
                    + " — " + e.getMessage(), e);
        } finally {
            if (isCurrent(run)) {
                ModelDownloadDao dao = getDaoSafely();
                if (dao != null) {
                try {
                    ModelDownloadEntity e = dao.getByName(entry.modelName);
                    if (e != null) {
                        int pct = computePct(e);
                        notifyProgress(entry.modelName, pct,
                                subTextForStatus(e.status, entry.modelName, pct));
                    }
                } catch (Exception ignored) {
                    // 终态通知失败不掩盖下载结果
                }
                }
            }
            finishRun(run);
        }
    }

    @Override
    public void onDestroy() {
        // 与首次启动失败路径（A-008）同一合规语义：服务被系统 stop（Android 15+ dataSync
        // 限制等）后，失去前台资格的分钟级下载不得在 Host 池中隐形续跑。
        // cancel 会保留 .tmp 断点与 DAO 状态；WorkManager 仅负责编排下一次受约束的显式启动。
        DownloadRun run;
        synchronized (runLock) {
            run = currentRun;
            currentRun = null;
            if (run != null) run.active.set(false);
        }
        if (run != null) cancelProgressPoll(run);
        if (run != null) {
            DownloadRuntime runtime = downloadRuntime();
            ModelDownloadManager manager =
                    runtime != null ? runtime.modelDownloadManager() : null;
            if (manager != null) {
                try {
                    manager.cancel(run.modelName);
                } catch (Exception e) {
                    Log.w(TAG, "[DownloadService] onDestroy cancel failed: " + e.getMessage());
                }
            }
        }
        super.onDestroy();
    }

    /**
     * Cancels the old foreground operation before publishing the new one.  Old workers may take
     * time to observe cancellation, so they retain their token but can no longer affect this
     * service's current state.
     */
    @NonNull
    private DownloadRun replaceRun(@NonNull String modelName, int startId,
            @NonNull ModelDownloadManager manager) {
        DownloadRun previous;
        DownloadRun next = new DownloadRun(nextRunId.incrementAndGet(), modelName, startId);
        synchronized (runLock) {
            previous = currentRun;
            currentRun = next;
            if (previous != null) {
                previous.active.set(false);
                cancelProgressPoll(previous);
            }
        }
        if (previous != null) {
            try {
                manager.cancel(previous.modelName);
            } catch (Exception e) {
                Log.w(TAG, "[DownloadService] cancel previous run failed: " + e.getMessage());
            }
        }
        return next;
    }

    private boolean isCurrent(@NonNull DownloadRun run) {
        return run.active.get() && currentRun == run;
    }

    /** Only the owner of the current run may stop this started service. */
    private void finishRun(@NonNull DownloadRun run) {
        boolean ownsRun;
        synchronized (runLock) {
            ownsRun = currentRun == run;
            run.active.set(false);
            if (ownsRun) currentRun = null;
        }
        cancelProgressPoll(run);
        if (ownsRun) {
            // Do not let an older start command stop a later start command.
            stopSelfResult(run.startId);
        }
    }

    private static final class DownloadRun {
        final long id;
        final String modelName;
        final int startId;
        final AtomicBoolean active = new AtomicBoolean(true);
        final AtomicReference<ScheduledFuture<?>> progressPoll = new AtomicReference<>();

        DownloadRun(long id, String modelName, int startId) {
            this.id = id;
            this.modelName = modelName;
            this.startId = startId;
        }
    }

    // ===== 通知 =====

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(com.matrix.agent.R.string.download_notification_channel),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(com.matrix.agent.R.string.download_notification_channel_description));
        channel.setSound(null, null);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    /** API 30+ 走 3 参重载显式指定类型；API 28/29 走 2 参（类型由 manifest 提供）。 */
    @SuppressWarnings("deprecation")
    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void notifyProgress(String modelName, int pct, String subText) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        nm.notify(NOTIFICATION_ID, buildNotification(modelName, pct, subText));
    }

    private Notification buildNotification(String modelName, int pct, String subText) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(getString(com.matrix.agent.R.string.download_notification_title))
                .setContentText(subText)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(mainContentIntent())
                .build();
    }

    @Nullable
    private PendingIntent mainContentIntent() {
        try {
            Intent intent = new Intent(com.matrix.agent.api.common.MatrixServiceConstants.ACTION_OPEN_DOWNLOADS)
                    .setPackage(com.matrix.agent.api.common.MatrixServiceConstants.LAUNCHER_PACKAGE);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
            return PendingIntent.getActivity(this, 0, intent, flags);
        } catch (Exception e) {
            return null;
        }
    }

    // ===== 辅助 =====

    @Nullable
    private DownloadRuntime downloadRuntime() {
        try {
            return ((DownloadRuntimeProvider) getApplication()).downloadRuntime();
        } catch (ClassCastException e) {
            return null;
        }
    }

    @Nullable
    private ModelDownloadDao getDaoSafely() {
        DownloadRuntime runtime = downloadRuntime();
        return runtime != null ? runtime.modelDownloadDao() : null;
    }

    private static int computePct(ModelDownloadEntity e) {
        if (e == null || e.totalBytes <= 0) return 0;
        long d = Math.max(0, e.downloadedBytes);
        long pct = d * 100 / e.totalBytes;
        if (pct < 0) pct = 0;
        if (pct > 100) pct = 100;
        return (int) pct;
    }

    private static boolean isTerminal(String status) {
        return ModelDownloadManager.STATUS_COMPLETED.equals(status)
                || ModelDownloadManager.STATUS_FAILED.equals(status);
    }

    private String subTextForStatus(String status, String modelName, int pct) {
        if (ModelDownloadManager.STATUS_COMPLETED.equals(status)) {
            return getString(com.matrix.agent.R.string.download_completed, modelName);
        } else if (ModelDownloadManager.STATUS_FAILED.equals(status)) {
            return getString(com.matrix.agent.R.string.download_failed, modelName);
        } else if (ModelDownloadManager.STATUS_DOWNLOADING.equals(status)) {
            return getString(com.matrix.agent.R.string.download_progress, modelName, pct);
        } else {
            return getString(com.matrix.agent.R.string.download_waiting, modelName);
        }
    }

    /** 下载执行池：Host 全局预算的 network/download 池（4/16），本 Service 不再自建池。 */
    @NonNull
    private ExecutorService workExecutor() {
        DownloadRuntime runtime = downloadRuntime();
        if (runtime == null || runtime.downloadExecutor() == null) {
            throw new IllegalStateException("download runtime unavailable");
        }
        return runtime.downloadExecutor();
    }

    @NonNull
    private ScheduledExecutorService progressScheduler() {
        DownloadRuntime runtime = downloadRuntime();
        if (runtime == null || runtime.timerExecutor() == null) {
            throw new IllegalStateException("download runtime unavailable");
        }
        return runtime.timerExecutor();
    }

    private static void cancelProgressPoll(@NonNull DownloadRun run) {
        ScheduledFuture<?> future = run.progressPoll.getAndSet(null);
        if (future != null) future.cancel(false);
    }
}
