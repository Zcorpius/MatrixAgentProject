package com.matrix.agent.download;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;


/** Starts the visible FGS after durable storage admission and an active-network check. */
public final class ModelDownloadStartWorker extends Worker {
    private static final String TAG = "MatrixAgent";
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
        if (!hasInternetCapableNetwork()) {
            Log.i(TAG, "[ModelDownload] waiting for an Internet-capable network: " + model);
            return Result.retry();
        }
        try {
            DownloadService.start(getApplicationContext(),
                    new ModelMarketClient.ModelEntry(model,
                            description == null ? model : description, sizeGb, repository));
            Log.i(TAG, "[ModelDownload] foreground transfer requested: " + model);
            return Result.success();
        } catch (RuntimeException denied) {
            Log.w(TAG, "[ModelDownload] foreground transfer start denied: " + model, denied);
            // Android may reject a background FGS start. Do not turn this Worker into a hidden
            // downloader; a bounded retry lets the constrained, user-visible path try again.
            return getRunAttemptCount() < 3 ? Result.retry() : Result.failure();
        }
    }

    private boolean hasInternetCapableNetwork() {
        ConnectivityManager connectivity = getApplicationContext()
                .getSystemService(ConnectivityManager.class);
        if (connectivity == null) return false;
        Network active = connectivity.getActiveNetwork();
        NetworkCapabilities capabilities = active == null ? null
                : connectivity.getNetworkCapabilities(active);
        // The actual HTTPS request decides reachability. Requiring VALIDATED here would repeat
        // JobScheduler's false negative on usable, partially validated Wi-Fi networks.
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private DownloadRuntime downloadRuntime() {
        try {
            return ((DownloadRuntimeProvider) getApplicationContext()).downloadRuntime();
        } catch (ClassCastException notHostApplication) {
            return null;
        }
    }
}
