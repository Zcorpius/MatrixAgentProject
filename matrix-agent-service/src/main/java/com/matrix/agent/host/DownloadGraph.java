package com.matrix.agent.host;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import com.matrix.agent.data.db.MatrixDatabase;
import com.matrix.agent.data.db.ModelDownloadDao;
import com.matrix.agent.download.ModelDownloadManager;

/** Owns download persistence recovery; it never constructs or owns the SQLCipher database. */
final class DownloadGraph {
    private static final String TAG = "MatrixAgent";
    @Nullable private final ModelDownloadDao dao;
    @Nullable private final ModelDownloadManager manager;

    DownloadGraph(Context appContext, @Nullable MatrixDatabase database) {
        dao = database == null ? null : database.modelDownloadDao();
        manager = dao == null ? null : new ModelDownloadManager(appContext, dao);
        if (manager != null) {
            try {
                manager.syncDaoWithFileSystem();
                manager.resumeDownloadIfNeeded();
            } catch (Exception error) {
                Log.w(TAG, "[DownloadGraph] state recovery failed", error);
            }
        }
    }

    @Nullable ModelDownloadDao dao() { return dao; }
    @Nullable ModelDownloadManager manager() { return manager; }
}
