package com.matrix.agent.launcher.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.matrix.agent.api.download.ModelCatalogItem;
import com.matrix.agent.api.download.ModelDownloadInfo;
import com.matrix.agent.api.model.ModelOperationHandle;
import com.matrix.agent.client.DownloadManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** SDK-backed download data source; URLs, hashes and file paths never enter Launcher UI state. */
public final class DownloadRepository {
    private final LauncherHostGateway gateway;
    public DownloadRepository(LauncherHostGateway gateway) { this.gateway = gateway; }
    public boolean isHostConnected() { return gateway.isConnected(); }
    public ScheduledFuture<?> scheduleWithFixedDelay(@NonNull Runnable task, long delay,
            @NonNull TimeUnit unit) { return gateway.scheduleWithFixedDelay(task, delay, unit); }
    public void snapshot(@NonNull Consumer<LauncherHostGateway.Result<Snapshot>> receiver) {
        gateway.execute(agent -> {
            DownloadManager manager = agent.getDownloadManager();
            return manager == null ? null : new Snapshot(manager.listCatalog(), manager.listDownloads());
        }, receiver);
    }
    public void refreshCatalog(@NonNull IntConsumer completion,
            @NonNull Consumer<LauncherHostGateway.Result<ModelOperationHandle>> receiver) {
        gateway.execute(agent -> {
            DownloadManager manager = agent.getDownloadManager();
            return manager == null ? null : manager.refreshCatalog(UUID.randomUUID().toString(),
                    (operation, code) -> gateway.dispatchToMain(() -> completion.accept(code)));
        }, receiver);
    }
    public void install(@NonNull String catalogId, @NonNull IntConsumer completion,
            @NonNull Consumer<LauncherHostGateway.Result<ModelOperationHandle>> receiver) {
        gateway.execute(agent -> {
            DownloadManager manager = agent.getDownloadManager();
            return manager == null ? null : manager.install(catalogId, UUID.randomUUID().toString(),
                    (operation, code) -> gateway.dispatchToMain(() -> completion.accept(code)));
        }, receiver);
    }
    public void pause(@NonNull String catalogId,
            @NonNull Consumer<LauncherHostGateway.Result<ModelOperationHandle>> receiver) { immediate(catalogId, 0, null, receiver); }
    public void resume(@NonNull String catalogId, @NonNull IntConsumer completion,
            @NonNull Consumer<LauncherHostGateway.Result<ModelOperationHandle>> receiver) { immediate(catalogId, 1, completion, receiver); }
    public void delete(@NonNull String catalogId, @NonNull IntConsumer completion,
            @NonNull Consumer<LauncherHostGateway.Result<ModelOperationHandle>> receiver) {
        immediate(catalogId, 2, completion, receiver);
    }
    private void immediate(String catalogId, int command, @Nullable IntConsumer completion,
            Consumer<LauncherHostGateway.Result<ModelOperationHandle>> receiver) {
        gateway.execute(agent -> {
            DownloadManager manager = agent.getDownloadManager();
            if (manager == null) return null;
            if (command == 0) return manager.pause(catalogId, UUID.randomUUID().toString());
            if (command == 2) return manager.delete(catalogId, UUID.randomUUID().toString(),
                    (operation, code) -> gateway.dispatchToMain(() -> completion.accept(code)));
            return manager.resume(catalogId, UUID.randomUUID().toString(),
                    (operation, code) -> gateway.dispatchToMain(() -> completion.accept(code)));
        }, receiver);
    }
    public static final class Snapshot {
        @NonNull public final List<ModelCatalogItem> catalog;
        @NonNull public final List<ModelDownloadInfo> downloads;
        Snapshot(@Nullable List<ModelCatalogItem> catalog, @Nullable List<ModelDownloadInfo> downloads) {
            this.catalog = Collections.unmodifiableList(catalog == null ? Collections.emptyList() : new ArrayList<>(catalog));
            this.downloads = Collections.unmodifiableList(downloads == null ? Collections.emptyList() : new ArrayList<>(downloads));
        }
    }
}
