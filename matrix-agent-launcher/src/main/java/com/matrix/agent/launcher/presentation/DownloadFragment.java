package com.matrix.agent.launcher.presentation;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.matrix.agent.api.download.ModelCatalogItem;
import com.matrix.agent.api.download.ModelDownloadInfo;
import com.matrix.agent.launcher.LauncherActivity;
import com.matrix.agent.launcher.R;

import java.util.HashMap;
import java.util.Map;

/** Download dashboard: Host-owned catalog, status and commands rendered as immutable state. */
public final class DownloadFragment extends Fragment {
    private LinearLayout activeRows;
    private LinearLayout catalogRows;
    private LinearLayout installedRows;
    private TextView notice;
    private DownloadViewModel viewModel;
    private boolean connected;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup parent, @Nullable Bundle state) {
        LauncherActivity activity = activity();
        View root = inflater.inflate(R.layout.fragment_download, parent, false);
        viewModel = new ViewModelProvider(requireActivity(), activity.viewModelFactory())
                .get(DownloadViewModel.class);
        activeRows = root.findViewById(R.id.download_active_list);
        catalogRows = root.findViewById(R.id.download_catalog_list);
        installedRows = root.findViewById(R.id.download_installed_list);
        notice = root.findViewById(R.id.download_notice);
        root.findViewById(R.id.download_refresh).setOnClickListener(ignored -> viewModel.refreshMarket());
        viewModel.state().observe(getViewLifecycleOwner(), this::render);
        new ViewModelProvider(requireActivity(), activity.viewModelFactory()).get(LauncherViewModel.class)
                .connectionState().observe(getViewLifecycleOwner(), value -> {
                    connected = viewModel.isHostConnected();
                    if (connected) viewModel.refresh();
                });
        return root;
    }

    @Override public void onStart() { super.onStart(); viewModel.startPolling(); }
    @Override public void onStop() { viewModel.stopPolling(); super.onStop(); }

    private void render(@NonNull DownloadViewModel.State value) {
        renderNotice(value);
        activeRows.removeAllViews();
        catalogRows.removeAllViews();
        installedRows.removeAllViews();
        Map<String, ModelDownloadInfo> downloads = new HashMap<>();
        for (ModelDownloadInfo item : value.downloads) downloads.put(item.modelId, item);
        for (ModelCatalogItem item : value.catalog) {
            ModelDownloadInfo download = downloads.get(item.catalogModelId);
            if (item.installed || state(download) == ModelDownloadInfo.DOWNLOAD_STATE_COMPLETED) {
                installedRows.addView(modelCard(item, download), spacing());
            } else if (isActive(download)) {
                activeRows.addView(modelCard(item, download), spacing());
            } else {
                catalogRows.addView(modelCard(item, download), spacing());
            }
        }
        if (activeRows.getChildCount() == 0) activeRows.addView(empty(R.string.download_active_empty));
        if (installedRows.getChildCount() == 0) installedRows.addView(empty(R.string.download_installed_empty));
        if (catalogRows.getChildCount() == 0) {
            catalogRows.addView(empty(R.string.download_catalog_empty));
        }
    }

    private void renderNotice(DownloadViewModel.State value) {
        switch (value.notice) {
            case HOST_UNAVAILABLE: notice.setText(R.string.host_not_connected); break;
            case CATALOG_READY: notice.setText(value.catalog.isEmpty()
                    ? "还没有市场目录。点“刷新市场”获取可下载模型。"
                    : "市场和本地库已同步；下载进度会自动更新。"); break;
            case MARKET_REFRESHING: notice.setText("Host 正在刷新模型市场…"); break;
            case MARKET_REFRESHED: notice.setText("模型市场已刷新，正在同步本地模型库。"); break;
            case MARKET_REFRESH_FAILED: notice.setText("模型市场刷新失败，错误码=" + value.code + "；仍可使用上次缓存。"); break;
            case PREPARING: notice.setText(getString(R.string.download_preparing, "")); break;
            case INSTALLED: notice.setText(getString(R.string.download_installed, "")); break;
            case INSTALL_FAILED: notice.setText(getString(R.string.download_install_failed, value.code)); break;
            case START_REJECTED: notice.setText(R.string.download_start_unavailable); break;
            case PAUSED: notice.setText(getString(R.string.download_paused, "")); break;
            case PAUSE_REJECTED: notice.setText(R.string.download_pause_rejected); break;
            case COMPLETED: notice.setText(getString(R.string.download_completed, "")); break;
            case RESUME_FAILED: notice.setText(getString(R.string.download_resume_failed, value.code)); break;
            case RESUME_REJECTED: notice.setText(R.string.download_resume_rejected); break;
            case DELETING: notice.setText(getString(R.string.download_deleting, "")); break;
            case DELETE_REJECTED: notice.setText(R.string.download_delete_rejected); break;
            default: break;
        }
    }

    private View modelCard(ModelCatalogItem item, @Nullable ModelDownloadInfo download) {
        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        TextView source = new TextView(requireContext());
        source.setText(item.installed ? "LOCAL LIBRARY" : "MNN MARKET");
        source.setTextColor(ContextCompat.getColor(requireContext(), R.color.matrix_accent));
        source.setTextSize(10); source.setTypeface(Typeface.DEFAULT_BOLD); card.addView(source);
        TextView title = new TextView(requireContext());
        title.setText(item.displayName);
        title.setTextColor(ContextCompat.getColor(requireContext(), R.color.matrix_text));
        title.setTextSize(17); title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = dp(3); card.addView(title, titleParams);
        TextView id = muted(getString(R.string.download_model_id, item.catalogModelId));
        card.addView(id, smallTop());
        TextView state = muted(describe(item, download));
        card.addView(state, smallTop());
        if (download != null && shouldShowProgress(download)) {
            int percent = percent(download);
            ProgressBar bar = new ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100); bar.setProgress(percent); bar.setIndeterminate(download.bytesTotal <= 0
                    && download.state == ModelDownloadInfo.DOWNLOAD_STATE_DOWNLOADING);
            LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(8));
            progressParams.topMargin = dp(10); card.addView(bar, progressParams);
            TextView progressLabel = muted(getString(R.string.download_progress_label, percent));
            card.addView(progressLabel, smallTop());
        }
        LinearLayout actions = new LinearLayout(requireContext()); actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)); actionsParams.topMargin = dp(11);
        if (item.installed || state(download) == ModelDownloadInfo.DOWNLOAD_STATE_COMPLETED) {
            Button use = new Button(requireContext()); use.setText("端侧选用"); use.setBackgroundResource(R.drawable.bg_primary); use.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white)); use.setEnabled(connected); use.setOnClickListener(v -> activity().showOnDeviceModel(item.catalogModelId));
            actions.addView(use, new LinearLayout.LayoutParams(0, dp(44), 1));
            Button remove = action(item, download); LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(0, dp(44), 1); removeParams.leftMargin = dp(8); actions.addView(remove, removeParams);
        } else {
            actions.addView(action(item, download), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        }
        card.addView(actions, actionsParams);
        return card;
    }

    private Button action(ModelCatalogItem item, @Nullable ModelDownloadInfo download) {
        int state = state(download);
        Button action = new Button(requireContext());
        boolean installed = item.installed || state == ModelDownloadInfo.DOWNLOAD_STATE_COMPLETED;
        if (installed) {
            action.setText("删除");
            action.setBackgroundResource(R.drawable.bg_outline);
            action.setTextColor(ContextCompat.getColor(requireContext(), R.color.matrix_primary_dark));
            action.setOnClickListener(ignored -> confirmDelete(item));
        } else if (state == ModelDownloadInfo.DOWNLOAD_STATE_DOWNLOADING) {
            action.setText(R.string.download_pause);
            action.setBackgroundResource(R.drawable.bg_outline);
            action.setTextColor(ContextCompat.getColor(requireContext(), R.color.matrix_primary_dark));
            action.setOnClickListener(ignored -> viewModel.pause(item));
        } else if (state == ModelDownloadInfo.DOWNLOAD_STATE_PAUSED
                || state == ModelDownloadInfo.DOWNLOAD_STATE_FAILED) {
            action.setText(R.string.download_resume);
            action.setBackgroundResource(R.drawable.bg_primary);
            action.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white));
            action.setOnClickListener(ignored -> viewModel.resume(item));
        } else {
            action.setText(R.string.download_install);
            action.setBackgroundResource(R.drawable.bg_primary);
            action.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.white));
            action.setOnClickListener(ignored -> viewModel.install(item));
        }
        action.setEnabled(connected);
        return action;
    }

    private void confirmDelete(ModelCatalogItem item) {
        new AlertDialog.Builder(requireContext()).setTitle(R.string.download_delete_title)
                .setMessage(getString(R.string.download_delete_message, item.displayName))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (dialog, ignored) -> viewModel.delete(item)).show();
    }

    private String describe(ModelCatalogItem item, @Nullable ModelDownloadInfo download) {
        String size = item.sizeBytes > 0 ? humanSize(item.sizeBytes) : getString(R.string.download_unknown_size);
        if (item.installed) return getString(R.string.download_status_installed) + " · " + size;
        if (download == null) return getString(R.string.download_status_none) + " · " + size;
        String progress = download.bytesTotal > 0 ? getString(R.string.download_progress,
                humanSize(download.bytesDownloaded), humanSize(download.bytesTotal), percent(download))
                : humanSize(download.bytesDownloaded);
        switch (download.state) {
            case ModelDownloadInfo.DOWNLOAD_STATE_DOWNLOADING:
                return getString(R.string.download_status_downloading, progress);
            case ModelDownloadInfo.DOWNLOAD_STATE_PAUSED:
                return getString(R.string.download_status_paused, progress);
            case ModelDownloadInfo.DOWNLOAD_STATE_COMPLETED:
                return getString(R.string.download_status_completed);
            case ModelDownloadInfo.DOWNLOAD_STATE_FAILED:
                return getString(R.string.download_status_failed, download.errorCode, progress);
            default: return getString(R.string.download_status_waiting) + " · " + size;
        }
    }

    private static int state(@Nullable ModelDownloadInfo item) {
        return item == null ? ModelDownloadInfo.DOWNLOAD_STATE_IDLE : item.state;
    }
    private static boolean isActive(@Nullable ModelDownloadInfo item) {
        return item != null && item.state != ModelDownloadInfo.DOWNLOAD_STATE_IDLE
                && item.state != ModelDownloadInfo.DOWNLOAD_STATE_COMPLETED;
    }
    private static boolean shouldShowProgress(ModelDownloadInfo item) {
        return item.state == ModelDownloadInfo.DOWNLOAD_STATE_DOWNLOADING
                || item.state == ModelDownloadInfo.DOWNLOAD_STATE_PAUSED
                || item.state == ModelDownloadInfo.DOWNLOAD_STATE_FAILED;
    }
    private static int percent(ModelDownloadInfo item) {
        if (item.bytesTotal <= 0) return 0;
        return (int) Math.max(0, Math.min(100, item.bytesDownloaded * 100L / item.bytesTotal));
    }
    private TextView empty(int message) { return muted(getString(message)); }
    private TextView muted(String text) {
        TextView value = new TextView(requireContext()); value.setText(text);
        value.setTextColor(ContextCompat.getColor(requireContext(), R.color.matrix_muted));
        value.setTextSize(13); return value;
    }
    private LinearLayout.LayoutParams spacing() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(8); return params;
    }
    private LinearLayout.LayoutParams smallTop() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(4); return params;
    }
    private String humanSize(long bytes) {
        return android.text.format.Formatter.formatShortFileSize(requireContext(), Math.max(0L, bytes));
    }
    private int dp(int value) { return activity().dp(value); }
    private LauncherActivity activity() { return (LauncherActivity) requireActivity(); }
}
