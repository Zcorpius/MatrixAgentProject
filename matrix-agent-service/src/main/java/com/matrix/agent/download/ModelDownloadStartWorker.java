package com.matrix.agent.download;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;


/** Starts the visible FGS only after WorkManager has admitted its network/storage constraints. */
public final class ModelDownloadStartWorker extends Worker {
    public ModelDownloadStartWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    @NonNull @Override public Result doWork() {
        String model = getInputData().getString(ModelDownloadWorkScheduler.KEY_MODEL_NAME);
        String repository = getInputData().getString(ModelDownloadWorkScheduler.KEY_REPOSITORY);
        String description = getInputData().getString(ModelDownloadWorkScheduler.KEY_DESCRIPTION);
        double sizeGb = getInputData().getDouble(ModelDownloadWorkScheduler.KEY_SIZE_GB, 0d);
        if (!ModelDownloadManager.isSafeModelName(model) || repository == null || repository.isEmpty()) {
            return Result.failure();
        }
        DownloadRuntime runtime = downloadRuntime();
        if (runtime == null || !runtime.isDownloadRecoveryComplete()
                || runtime.modelDownloadManager() == null) {
            // Host cold start/recovery is transient. Retrying cannot transfer data behind a
            // missing FGS because this worker only starts DownloadService after readiness.
            return Result.retry();
        }
        try {
            DownloadService.start(getApplicationContext(),
                    new ModelMarketClient.ModelEntry(model,
                            description == null ? model : description, sizeGb, repository));
            return Result.success();
        } catch (RuntimeException denied) {
            // Android may reject a background FGS start. Do not turn this Worker into a hidden
            // downloader; a bounded retry lets the constrained, user-visible path try again.
            return getRunAttemptCount() < 3 ? Result.retry() : Result.failure();
        }
    }

    private DownloadRuntime downloadRuntime() {
        try {
            return ((DownloadRuntimeProvider) getApplicationContext()).downloadRuntime();
        } catch (ClassCastException notHostApplication) {
            return null;
        }
    }
}
