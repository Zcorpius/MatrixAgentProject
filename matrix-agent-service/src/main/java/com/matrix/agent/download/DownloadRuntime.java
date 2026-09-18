package com.matrix.agent.download;

import androidx.annotation.Nullable;

import com.matrix.agent.data.db.ModelDownloadDao;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Narrow process-runtime contract required by Android download entry points.
 *
 * <p>The download domain owns this contract.  The host composition root implements it, while a
 * {@code Service} or {@code Worker} sees only the dependencies it actually consumes.  This keeps
 * process-recreation support without importing Host implementation types into download.</p>
 */
public interface DownloadRuntime {
    @Nullable ModelDownloadManager modelDownloadManager();
    @Nullable ModelDownloadDao modelDownloadDao();
    boolean isDownloadRecoveryComplete();
    ExecutorService downloadExecutor();
    ScheduledExecutorService timerExecutor();
}
