package com.matrix.agent.launcher.presentation;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.matrix.agent.api.download.ModelCatalogItem;
import com.matrix.agent.api.download.ModelDownloadInfo;
import com.matrix.agent.launcher.data.DownloadRepository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Owns catalog polling and all model-download commands. */
public final class DownloadViewModel extends ViewModel {
    private static final long POLL_INTERVAL_SECONDS = 1L;
    private final DownloadRepository repository;
    private final MutableLiveData<State> state = new MutableLiveData<>(State.initial());
    private final OperationEpoch operations = new OperationEpoch();
    private State current = State.initial();
    @Nullable private ScheduledFuture<?> polling;
    private boolean initialMarketRefreshRequested;

    public DownloadViewModel(DownloadRepository repository) { this.repository = repository; }
    public LiveData<State> state() { return state; }
    public boolean isHostConnected() { return repository.isHostConnected(); }

    /** Called by the View lifecycle; scheduling and work execution remain owned by this ViewModel. */
    public void startPolling() {
        if (polling != null && !polling.isCancelled()) return;
        refresh();
        polling = repository.scheduleWithFixedDelay(this::refresh, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }
    public void stopPolling() {
        if (polling != null) polling.cancel(false);
        polling = null;
    }

    public void refresh() {
        refresh(operations.current());
    }

    private void refresh(long expectedOperation) {
        repository.snapshot(result -> {
            if (!operations.isCurrent(expectedOperation)) return;
            if (!result.isSuccess() || result.value == null) {
                update(current.withNotice(Notice.HOST_UNAVAILABLE, 0));
                return;
            }
            DownloadRepository.Snapshot value = result.value;
            State next = new State(value.catalog, value.downloads, Notice.CATALOG_READY, 0);
            // Polling is only a transport concern. Do not rebuild the whole screen every second
            // when the Host projection is unchanged; this also keeps accessibility/UI idle.
            if (!current.sameProjection(next) || current.notice != Notice.CATALOG_READY) update(next);
            // A new device has no Host-private cache. Hydrate it automatically once so the first
            // visit never looks like an empty, non-functional market. Further refreshes remain
            // user initiated; polling must never repeatedly hit the network.
            if (next.catalog.isEmpty() && !initialMarketRefreshRequested) {
                initialMarketRefreshRequested = true;
                refreshMarket();
            }
        });
    }

    /** Refreshes the Host-owned remote market cache, then redraws both market and local library. */
    public void refreshMarket() {
        final long operation = operations.begin();
        initialMarketRefreshRequested = true;
        update(current.withNotice(Notice.MARKET_REFRESHING, 0));
        repository.refreshCatalog(code -> {
            if (!operations.isCurrent(operation)) return;
            update(current.withNotice(code == 0 ? Notice.MARKET_REFRESHED : Notice.MARKET_REFRESH_FAILED, code));
            refresh(operation);
        }, result -> {
            if (!operations.isCurrent(operation)) return;
            if (!result.isSuccess() || result.value == null) {
                update(current.withNotice(Notice.HOST_UNAVAILABLE, 0));
            }
        });
    }

    public void install(@NonNull ModelCatalogItem item) {
        final long operation = operations.begin();
        update(current.withNotice(Notice.PREPARING, 0));
        repository.install(item.catalogModelId, code -> {
            if (!operations.isCurrent(operation)) return;
            update(current.withNotice(code == 0 ? Notice.INSTALLED : Notice.INSTALL_FAILED, code));
            refresh(operation);
        }, result -> {
            if (!operations.isCurrent(operation)) return;
            if (!result.isSuccess() || result.value == null) {
                update(current.withNotice(Notice.START_REJECTED, 0));
            } else {
                refresh(operation);
            }
        });
    }

    public void pause(@NonNull ModelCatalogItem item) { immediate(item, Command.PAUSE); }
    public void resume(@NonNull ModelCatalogItem item) { immediate(item, Command.RESUME); }
    public void delete(@NonNull ModelCatalogItem item) { immediate(item, Command.DELETE); }

    private void immediate(@NonNull ModelCatalogItem item, @NonNull Command command) {
        final long operation = operations.begin();
        if (command == Command.PAUSE) {
            repository.pause(item.catalogModelId, result -> applyImmediate(operation, command, result));
        } else if (command == Command.DELETE) {
            repository.delete(item.catalogModelId, code -> {
                if (!operations.isCurrent(operation)) return;
                update(current.withNotice(code == 0 ? Notice.DELETED : Notice.DELETE_FAILED, code));
                refresh(operation);
            }, result -> applyImmediate(operation, command, result));
        } else {
            repository.resume(item.catalogModelId, code -> {
                if (!operations.isCurrent(operation)) return;
                update(current.withNotice(code == 0 ? Notice.COMPLETED : Notice.RESUME_FAILED, code));
                refresh(operation);
            }, result -> applyImmediate(operation, command, result));
        }
    }

    private void applyImmediate(long operation, @NonNull Command command,
            @NonNull com.matrix.agent.launcher.data.LauncherHostGateway.Result<?> result) {
            if (!operations.isCurrent(operation)) return;
            if (!result.isSuccess() || result.value == null) {
                update(current.withNotice(command == Command.PAUSE ? Notice.PAUSE_REJECTED
                        : command == Command.DELETE ? Notice.DELETE_REJECTED : Notice.RESUME_REJECTED, 0));
            } else {
                update(current.withNotice(command == Command.PAUSE ? Notice.PAUSED
                        : command == Command.DELETE ? Notice.DELETING : Notice.PREPARING, 0));
                refresh(operation);
            }
    }

    private void update(State next) { current = next; state.postValue(next); }
    @Override protected void onCleared() { stopPolling(); }

    private enum Command { PAUSE, RESUME, DELETE }
    public enum Notice {
        IDLE, HOST_UNAVAILABLE, CATALOG_READY, MARKET_REFRESHING, MARKET_REFRESHED, MARKET_REFRESH_FAILED,
        PREPARING, INSTALLED, INSTALL_FAILED, START_REJECTED,
        PAUSED, PAUSE_REJECTED, COMPLETED, RESUME_FAILED, RESUME_REJECTED,
        DELETING, DELETED, DELETE_FAILED, DELETE_REJECTED
    }
    public static final class State {
        @NonNull public final List<ModelCatalogItem> catalog;
        @NonNull public final List<ModelDownloadInfo> downloads;
        @NonNull public final Notice notice;
        public final int code;
        State(@NonNull List<ModelCatalogItem> catalog, @NonNull List<ModelDownloadInfo> downloads,
                @NonNull Notice notice, int code) {
            this.catalog = Collections.unmodifiableList(new ArrayList<>(catalog));
            this.downloads = Collections.unmodifiableList(new ArrayList<>(downloads));
            this.notice = notice;
            this.code = code;
        }
        static State initial() { return new State(Collections.emptyList(), Collections.emptyList(), Notice.IDLE, 0); }
        State withNotice(Notice notice, int code) { return new State(catalog, downloads, notice, code); }
        boolean sameProjection(@NonNull State other) {
            return sameCatalog(catalog, other.catalog) && sameDownloads(downloads, other.downloads);
        }
        private static boolean sameCatalog(List<ModelCatalogItem> left, List<ModelCatalogItem> right) {
            if (left.size() != right.size()) return false;
            for (int index = 0; index < left.size(); index++) {
                ModelCatalogItem a = left.get(index), b = right.get(index);
                if (!java.util.Objects.equals(a.catalogModelId, b.catalogModelId)
                        || !java.util.Objects.equals(a.displayName, b.displayName)
                        || !java.util.Objects.equals(a.version, b.version)
                        || a.sizeBytes != b.sizeBytes || a.installed != b.installed) return false;
            }
            return true;
        }
        private static boolean sameDownloads(List<ModelDownloadInfo> left, List<ModelDownloadInfo> right) {
            if (left.size() != right.size()) return false;
            for (int index = 0; index < left.size(); index++) {
                ModelDownloadInfo a = left.get(index), b = right.get(index);
                if (!java.util.Objects.equals(a.modelId, b.modelId) || a.state != b.state
                        || a.bytesDownloaded != b.bytesDownloaded || a.bytesTotal != b.bytesTotal
                        || a.errorCode != b.errorCode) return false;
            }
            return true;
        }
    }
}
