package com.matrix.agent.download;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
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
                .setRequiresStorageNotLow(true)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(ModelDownloadStartWorker.class)
                .setInputData(input)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                // Android's CONNECTED constraint can require a VALIDATED network. Some usable
                // networks are only PARTIAL_CONNECTIVITY (for example when validation probes
                // are blocked), so the worker checks for an INTERNET-capable active network.
                // The transfer itself still runs only in the foreground service.
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
