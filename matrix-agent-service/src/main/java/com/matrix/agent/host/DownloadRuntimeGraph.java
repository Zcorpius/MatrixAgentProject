package com.matrix.agent.host;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import com.matrix.agent.data.db.MatrixDatabase;
import com.matrix.agent.data.db.ModelDownloadDao;
import com.matrix.agent.download.ModelDownloadManager;
import com.matrix.agent.download.ModelDownloadWorkScheduler;
import com.matrix.agent.platform.MatrixHttpClient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns download persistence recovery; it never constructs or owns the SQLCipher database. */
final class DownloadRuntimeGraph {
    private static final String TAG = "MatrixAgent";
    @Nullable private final ModelDownloadDao dao;
    @Nullable private final ModelDownloadManager manager;
    private final ModelDownloadWorkScheduler workScheduler;
    private final AtomicBoolean recoveryComplete = new AtomicBoolean(false);

    DownloadRuntimeGraph(Context appContext, @Nullable MatrixDatabase database, ExecutorService databaseExecutor,
            MatrixHttpClient httpClient) {
        dao = database == null ? null : database.modelDownloadDao();
        manager = dao == null ? null : new ModelDownloadManager(appContext, dao, httpClient.download());
        workScheduler = new ModelDownloadWorkScheduler(appContext);
        if (manager == null) return;
        try {
            databaseExecutor.execute(() -> {
                try {
                    manager.syncDaoWithFileSystem();
                    manager.resumeDownloadIfNeeded();
                    recoveryComplete.set(true);
                } catch (Exception error) {
                    // Do not admit mutations against a partially reconciled on-disk download.
                    Log.w(TAG, "[DownloadRuntimeGraph] state recovery failed; admission stays closed", error);
                }
            });
        } catch (RejectedExecutionException unavailable) {
            Log.w(TAG, "[DownloadRuntimeGraph] state recovery was rejected; admission stays closed",
                    unavailable);
        }
    }

    @Nullable ModelDownloadDao dao() { return dao; }
    @Nullable ModelDownloadManager manager() { return manager; }
    ModelDownloadWorkScheduler workScheduler() { return workScheduler; }
    boolean isReady() { return manager != null && recoveryComplete.get(); }
}
