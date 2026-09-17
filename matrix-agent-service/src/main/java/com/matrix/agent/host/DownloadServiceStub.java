package com.matrix.agent.host;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.matrix.agent.api.common.MatrixErrorCode;
import com.matrix.agent.api.download.IDownloadService;
import com.matrix.agent.api.download.ModelCatalogItem;
import com.matrix.agent.api.download.ModelDownloadInfo;
import com.matrix.agent.api.model.IModelCallback;
import com.matrix.agent.api.model.ModelOperationHandle;
import com.matrix.agent.data.db.ModelDownloadEntity;
import com.matrix.agent.download.DownloadService;
import com.matrix.agent.download.ModelDownloadManager;
import com.matrix.agent.download.ModelMarketClient;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Host-owned MNN market, download progress and local-model lifecycle.
 *
 * <p>The Binder read path serves only an in-memory projection of the Host private cache. Remote
 * market refresh is explicitly requested and always runs on the bounded Host I/O executor.</p>
 */
final class DownloadServiceStub extends IDownloadService.Stub {
    private static final String CACHE_FILE = "mnn_model_market.json";
    private final Context appContext;
    private final ModelDownloadManager manager;
    private final com.matrix.agent.data.db.ModelDownloadDao dao;
    private final PersistenceGate persistenceGate;
    private final ModelServiceStub.CallerResolver callerResolver;
    private final ExecutorService io;
    private final ScheduledExecutorService scheduler;
    private final AtomicReference<List<CatalogEntry>> catalog;

    DownloadServiceStub(Context appContext, ModelDownloadManager manager,
            com.matrix.agent.data.db.ModelDownloadDao dao, ExecutorService io,
            ScheduledExecutorService scheduler,
            PersistenceGate persistenceGate, ModelServiceStub.CallerResolver callerResolver) {
        this.appContext = appContext.getApplicationContext();
        this.manager = manager;
        this.dao = dao;
        this.persistenceGate = persistenceGate;
        this.callerResolver = callerResolver;
        this.io = io;
        this.scheduler = scheduler;
        this.catalog = new AtomicReference<>(loadCachedCatalog());
    }

    @Override public List<ModelCatalogItem> listCatalog() {
        callerResolver.caller();
        List<ModelCatalogItem> out = new ArrayList<>();
        for (CatalogEntry entry : catalog.get()) {
            out.add(new ModelCatalogItem(entry.id, entry.name, entry.version, entry.sizeBytes,
                    isInstalled(entry.id)));
        }
        return out;
    }

    @Override public List<ModelDownloadInfo> listDownloads() {
        callerResolver.caller();
        List<ModelDownloadInfo> out = new ArrayList<>();
        if (dao == null) return out;
        for (ModelDownloadEntity item : dao.getAll()) {
            out.add(new ModelDownloadInfo(item.modelName, mapState(item.status), item.downloadedBytes,
                    item.totalBytes, MatrixErrorCode.SUCCESS));
        }
        return out;
    }

    @Override public ModelOperationHandle refreshCatalog(String operationId, IModelCallback callback) {
        callerResolver.caller();
        String safeOperation = HostInputValidator.requireOperationId(operationId);
        ModelOperationHandle pending = new ModelOperationHandle(safeOperation, "mnn-market",
                ModelOperationHandle.STATE_PENDING);
        try {
            io.execute(() -> {
                int code = MatrixErrorCode.SUCCESS;
                ModelOperationHandle result;
                try {
                    List<CatalogEntry> refreshed = toCatalog(ModelMarketClient.fetchModels(cacheFile()));
                    if (refreshed.isEmpty()) throw new IllegalStateException("market has no downloadable models");
                    catalog.set(refreshed);
                    result = new ModelOperationHandle(safeOperation, "mnn-market",
                            ModelOperationHandle.STATE_SUCCEEDED);
                } catch (Exception failure) {
                    android.util.Log.w("MatrixAgent", "[ModelMarket] refresh failed; cached catalog="
                            + catalog.get().size(), failure);
                    code = MatrixErrorCode.TASK_FAILED;
                    result = new ModelOperationHandle(safeOperation, "mnn-market",
                            ModelOperationHandle.STATE_FAILED);
                }
                notify(callback, result, code);
            });
        } catch (java.util.concurrent.RejectedExecutionException full) {
            return failed(safeOperation, "mnn-market", MatrixErrorCode.OVERLOADED, callback);
        }
        return pending;
    }

    @Override public ModelOperationHandle install(String modelId, String operationId,
            IModelCallback callback) {
        callerResolver.caller();
        HostInputValidator.requireOperationId(operationId);
        return start(modelId, operationId, callback);
    }

    @Override public ModelOperationHandle pause(String modelId, String operationId) {
        callerResolver.caller();
        HostInputValidator.requireOperationId(operationId);
        if (!persistenceGate.isAvailable() || manager == null) {
            return failed(operationId, modelId, MatrixErrorCode.PERSISTENCE_UNAVAILABLE, null);
        }
        manager.cancel(requireModel(modelId).id);
        return new ModelOperationHandle(operationId, modelId, ModelOperationHandle.STATE_SUCCEEDED);
    }

    @Override public ModelOperationHandle resume(String modelId, String operationId,
            IModelCallback callback) {
        callerResolver.caller();
        HostInputValidator.requireOperationId(operationId);
        return start(modelId, operationId, callback);
    }

    @Override public ModelOperationHandle delete(String modelId, String operationId,
            IModelCallback callback) {
        callerResolver.caller();
        String safeOperation = HostInputValidator.requireOperationId(operationId);
        final CatalogEntry entry = requireModel(modelId);
        if (!persistenceGate.isAvailable() || manager == null) {
            return failed(safeOperation, entry.id, MatrixErrorCode.PERSISTENCE_UNAVAILABLE, callback);
        }
        try {
            ModelOperationHandle pending = new ModelOperationHandle(safeOperation, entry.id,
                    ModelOperationHandle.STATE_PENDING);
            io.execute(() -> {
                try {
                    manager.delete(entry.id);
                    notify(callback, new ModelOperationHandle(safeOperation, entry.id,
                            ModelOperationHandle.STATE_SUCCEEDED), MatrixErrorCode.SUCCESS);
                } catch (RuntimeException failure) {
                    android.util.Log.w("MatrixAgent", "[ModelDownload] delete failed: " + entry.id,
                            failure);
                    notify(callback, new ModelOperationHandle(safeOperation, entry.id,
                            ModelOperationHandle.STATE_FAILED), MatrixErrorCode.TASK_FAILED);
                }
            });
            return pending;
        } catch (java.util.concurrent.RejectedExecutionException full) {
            return failed(safeOperation, entry.id, MatrixErrorCode.OVERLOADED, callback);
        }
    }

    private ModelOperationHandle start(String modelId, String operationId, IModelCallback callback) {
        CatalogEntry entry = requireModel(modelId);
        if (!persistenceGate.isAvailable() || manager == null) {
            return failed(operationId, entry.id, MatrixErrorCode.PERSISTENCE_UNAVAILABLE, callback);
        }
        try {
            Intent intent = new Intent(appContext, DownloadService.class)
                    .setAction(DownloadService.ACTION_DOWNLOAD)
                    .putExtra(DownloadService.EXTRA_MODEL_NAME, entry.id)
                    .putExtra(DownloadService.EXTRA_MODEL_SCOPE_REPO, entry.repo)
                    .putExtra(DownloadService.EXTRA_DESCRIPTION, entry.name)
                    .putExtra(DownloadService.EXTRA_SIZE_GB, entry.sizeGb);
            ContextCompat.startForegroundService(appContext, intent);
            ModelOperationHandle pending = new ModelOperationHandle(operationId, entry.id,
                    ModelOperationHandle.STATE_PENDING);
            watchCompletion(entry.id, pending, callback);
            return pending;
        } catch (Exception error) {
            return failed(operationId, entry.id, MatrixErrorCode.TASK_FAILED, callback);
        }
    }

    private void watchCompletion(String modelId, ModelOperationHandle pending, IModelCallback callback) {
        if (callback == null || dao == null) return;
        AtomicInteger polls = new AtomicInteger();
        AtomicReference<ScheduledFuture<?>> futureRef = new AtomicReference<>();
        android.os.IBinder.DeathRecipient death = () -> cancel(futureRef.get());
        try {
            callback.asBinder().linkToDeath(death, 0);
            ScheduledFuture<?> future = scheduler.scheduleWithFixedDelay(() -> {
                ModelDownloadEntity item = dao.getByName(modelId);
                if (item != null && !"DOWNLOADING".equals(item.status)) {
                    int code = "COMPLETED".equals(item.status)
                            ? MatrixErrorCode.SUCCESS : MatrixErrorCode.TASK_FAILED;
                    int state = code == MatrixErrorCode.SUCCESS ? ModelOperationHandle.STATE_SUCCEEDED
                            : ModelOperationHandle.STATE_FAILED;
                    notify(callback, new ModelOperationHandle(pending.operationId, modelId, state), code);
                    finishObserver(callback, death, futureRef.get());
                } else if (polls.incrementAndGet() >= 900) {
                    notify(callback, new ModelOperationHandle(pending.operationId, modelId,
                            ModelOperationHandle.STATE_FAILED), MatrixErrorCode.TIMED_OUT);
                    finishObserver(callback, death, futureRef.get());
                }
            }, 1L, 1L, TimeUnit.SECONDS);
            futureRef.set(future);
        } catch (android.os.RemoteException deadCallback) {
            cancel(futureRef.get());
        } catch (java.util.concurrent.RejectedExecutionException full) {
            finishObserver(callback, death, futureRef.get());
            notify(callback, new ModelOperationHandle(pending.operationId, modelId,
                    ModelOperationHandle.STATE_FAILED), MatrixErrorCode.OVERLOADED);
        }
    }

    private List<CatalogEntry> loadCachedCatalog() {
        try { return toCatalog(ModelMarketClient.loadCachedModels(cacheFile())); }
        catch (Exception ignored) { return Collections.emptyList(); }
    }

    private File cacheFile() { return new File(appContext.getFilesDir(), CACHE_FILE); }

    private static List<CatalogEntry> toCatalog(List<ModelMarketClient.ModelEntry> values) {
        List<CatalogEntry> out = new ArrayList<>();
        for (ModelMarketClient.ModelEntry value : values) {
            if (value.modelName == null || !value.modelName.matches("[A-Za-z0-9._-]{1,120}")
                    || value.modelScopeRepo == null
                    || !value.modelScopeRepo.matches("[A-Za-z0-9._-]{1,80}/[A-Za-z0-9._-]{1,120}")) {
                continue;
            }
            if (!Double.isFinite(value.sizeGb) || value.sizeGb < 0d) continue;
            double bytes = value.sizeGb * 1_000_000_000d;
            if (bytes > ModelDownloadManager.MAX_MODEL_BYTES) continue;
            long size = value.sizeGb == 0d ? 0L : Math.round(bytes);
            out.add(new CatalogEntry(value.modelName, safeText(value.description, value.modelName),
                    "MNN Market", size, value.sizeGb, value.modelScopeRepo));
        }
        return Collections.unmodifiableList(out);
    }

    private static String safeText(String value, String fallback) {
        if (value == null || value.isEmpty()) return fallback;
        return value.length() <= 160 ? value : value.substring(0, 160);
    }

    private CatalogEntry requireModel(String id) {
        if (id != null) for (CatalogEntry entry : catalog.get()) if (entry.id.equals(id)) return entry;
        throw new IllegalArgumentException("model is not in current Host market cache");
    }
    private boolean isInstalled(String id) {
        ModelDownloadEntity item = dao == null ? null : dao.getByName(id);
        return item != null && "COMPLETED".equals(item.status);
    }
    private static int mapState(String value) {
        if ("DOWNLOADING".equals(value)) return ModelDownloadInfo.DOWNLOAD_STATE_DOWNLOADING;
        if ("PAUSED".equals(value)) return ModelDownloadInfo.DOWNLOAD_STATE_PAUSED;
        if ("COMPLETED".equals(value)) return ModelDownloadInfo.DOWNLOAD_STATE_COMPLETED;
        if ("FAILED".equals(value)) return ModelDownloadInfo.DOWNLOAD_STATE_FAILED;
        return ModelDownloadInfo.DOWNLOAD_STATE_IDLE;
    }
    private static void finishObserver(IModelCallback callback,
            android.os.IBinder.DeathRecipient death, ScheduledFuture<?> future) {
        cancel(future);
        try { callback.asBinder().unlinkToDeath(death, 0); } catch (RuntimeException ignored) { }
    }
    private static void cancel(ScheduledFuture<?> future) { if (future != null) future.cancel(false); }
    private static ModelOperationHandle failed(String op, String id, int code, IModelCallback callback) {
        ModelOperationHandle result = new ModelOperationHandle(op, id, ModelOperationHandle.STATE_FAILED);
        notify(callback, result, code); return result;
    }
    private static void notify(IModelCallback callback, ModelOperationHandle handle, int code) {
        if (callback != null) try { callback.onModelOperationFinished(handle, code); }
        catch (android.os.RemoteException ignored) { }
    }

    private static final class CatalogEntry {
        final String id, name, version, repo;
        final long sizeBytes;
        final double sizeGb;
        CatalogEntry(String id, String name, String version, long sizeBytes, double sizeGb, String repo) {
            this.id = id; this.name = name; this.version = version; this.sizeBytes = sizeBytes;
            this.sizeGb = sizeGb; this.repo = repo;
        }
    }
}
