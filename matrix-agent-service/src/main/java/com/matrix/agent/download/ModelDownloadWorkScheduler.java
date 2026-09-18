package com.matrix.agent.download;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.OutOfQuotaPolicy;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/**
 * Durable admission scheduler for a user-requested model transfer.
 *
 * <p>WorkManager owns only constraints and restart-safe admission. The actual byte transfer stays
 * in {@link DownloadService}, whose foreground lifetime and Host-owned bounded worker preserve
 * the product's cancellation and visibility contract.</p>
 */
public final class ModelDownloadWorkScheduler {
    private static final String UNIQUE_PREFIX = "matrix-model-download-";
    static final String KEY_MODEL_NAME = "model_name";
    static final String KEY_REPOSITORY = "repository";
    static final String KEY_DESCRIPTION = "description";
    static final String KEY_SIZE_GB = "size_gb";

    private final WorkManager workManager;

    public ModelDownloadWorkScheduler(@NonNull Context context) {
        workManager = WorkManager.getInstance(context.getApplicationContext());
    }

    public void enqueue(@NonNull ModelMarketClient.ModelEntry entry) {
        Data input = new Data.Builder()
                .putString(KEY_MODEL_NAME, entry.modelName)
                .putString(KEY_REPOSITORY, entry.modelScopeRepo)
                .putString(KEY_DESCRIPTION, entry.description)
                .putDouble(KEY_SIZE_GB, entry.sizeGb)
                .build();
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresStorageNotLow(true)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(ModelDownloadStartWorker.class)
                .setInputData(input)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                // A user click should normally start promptly; quota exhaustion falls back to the
                // same constrained work rather than bypassing Android's background policy.
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .addTag(uniqueWorkName(entry.modelName))
                .build();
        workManager.enqueueUniqueWork(uniqueWorkName(entry.modelName), ExistingWorkPolicy.REPLACE,
                request);
    }

    public void cancel(@NonNull String modelName) {
        workManager.cancelUniqueWork(uniqueWorkName(modelName));
    }

    static String uniqueWorkName(String modelName) {
        return UNIQUE_PREFIX + modelName;
    }
}
