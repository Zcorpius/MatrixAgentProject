package com.matrix.agent.host;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import com.matrix.agent.data.db.MatrixDatabase;
import com.matrix.agent.platform.AndroidKeyStoreMasterKeyProvider;
import com.matrix.agent.platform.MasterKeyProvider;

/**
 * Owns only the SQLCipher database boundary. Download lifecycle belongs to
 * {@link DownloadRuntimeGraph};
 * this graph intentionally exposes no domain manager.
 */
final class PersistenceRuntimeGraph {
    private static final String TAG = "MatrixAgent";
    @Nullable private final MatrixDatabase database;

    PersistenceRuntimeGraph(Context appContext) {
        database = createDatabaseSafely(appContext);
    }

    @Nullable MatrixDatabase database() { return database; }
    boolean available() { return database != null && database.isOpen(); }

    @Nullable
    private static MatrixDatabase createDatabaseSafely(Context appContext) {
        try {
            MasterKeyProvider keyProvider = new AndroidKeyStoreMasterKeyProvider(appContext);
            return MatrixDatabase.getInstance(appContext, keyProvider);
        } catch (Exception error) {
            Log.e(TAG, "[PersistenceRuntimeGraph] encrypted database unavailable; dependent domains "
                    + "stay fail-closed", error);
            return null;
        }
    }
}
