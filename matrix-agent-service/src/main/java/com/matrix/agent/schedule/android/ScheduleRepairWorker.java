package com.matrix.agent.schedule.android;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.*;
import com.matrix.agent.schedule.ScheduleRuntimeProvider;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Recovery only; precision comes from the single AlarmManager registration, never WorkManager. */
public final class ScheduleRepairWorker extends Worker {
    public ScheduleRepairWorker(@NonNull Context context, @NonNull WorkerParameters params) { super(context, params); }
    public static void enqueue(Context context) {
        WorkManager.getInstance(context).enqueueUniqueWork("matrix-schedule-repair", ExistingWorkPolicy.KEEP,
                new OneTimeWorkRequest.Builder(ScheduleRepairWorker.class).setInitialDelay(10, TimeUnit.SECONDS)
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build());
    }
    @NonNull @Override public Result doWork() {
        if (!(getApplicationContext() instanceof ScheduleRuntimeProvider provider)) return Result.failure();
        CountDownLatch completed = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean repaired = new java.util.concurrent.atomic.AtomicBoolean();
        provider.scheduleRuntime().wake("REPAIR", -1, System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime(), success -> { repaired.set(success); completed.countDown(); });
        try { return completed.await(6, TimeUnit.SECONDS) && repaired.get() ? Result.success() : Result.retry(); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return Result.retry(); }
    }
}
