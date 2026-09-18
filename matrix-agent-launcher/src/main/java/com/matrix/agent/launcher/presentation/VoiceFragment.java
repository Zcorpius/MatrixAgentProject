package com.matrix.agent.launcher.presentation;

import android.graphics.Typeface;
import android.os.Bundle;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.matrix.agent.launcher.LauncherActivity;
import com.matrix.agent.launcher.R;
import com.matrix.agent.api.common.MatrixErrorCode;
import com.matrix.agent.api.download.ModelDownloadInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Renders the controlled voice session and its safe, progressively-recognized text. */
public final class VoiceFragment extends Fragment {
    private static final String VOICE_PREFERENCES = "voice_presentation";
    private static final String MODEL_DOWNLOAD_EXPLAINED = "model_download_explained";
    private VoiceViewModel viewModel;
    private TextView status, guidance, finalTranscript, partialTranscript;
    private LinearLayout transfers, installedModels;
    private TextView modelNotice;
    private Button start, finish, interrupt, refresh;
    @Nullable private VoiceViewModel.State rendered;
    private boolean connected;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup parent, @Nullable Bundle savedInstanceState) {
        viewModel = new ViewModelProvider(requireActivity(), activity().viewModelFactory())
                .get(VoiceViewModel.class);
        ScrollView scroll = new ScrollView(requireContext());
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(22), dp(20), dp(32));
        scroll.addView(root);

        root.addView(eyebrow(getString(R.string.voice_eyebrow)));
        root.addView(heading(getString(R.string.voice_title)), top(7));
        root.addView(copy(getString(R.string.voice_intro)), top(6));

        root.addView(eyebrow(getString(R.string.voice_session_title)), top(24));
        LinearLayout sessionCard = card();
        status = new TextView(requireContext());
        status.setBackgroundResource(R.drawable.bg_notice);
        status.setPadding(dp(12), dp(8), dp(12), dp(8));
        status.setTextColor(color(R.color.matrix_primary_dark));
        status.setTextSize(15);
        status.setTypeface(Typeface.DEFAULT_BOLD);
        sessionCard.addView(status);
        guidance = copy(getString(R.string.voice_session_guidance));
        sessionCard.addView(guidance, top(12));
        LinearLayout controls = new LinearLayout(requireContext());
        controls.setOrientation(LinearLayout.HORIZONTAL);
        start = button(R.string.voice_start, true, v -> beginVoiceSession());
        finish = button(R.string.voice_finish, false, v -> viewModel.finishRecording());
        controls.addView(start, weight());
        controls.addView(finish, weightWithStart());
        sessionCard.addView(controls, top(14));
        LinearLayout utilities = new LinearLayout(requireContext());
        utilities.setOrientation(LinearLayout.HORIZONTAL);
        interrupt = button(R.string.voice_interrupt, false, v -> viewModel.interrupt());
        refresh = button(R.string.voice_refresh, false, v -> viewModel.refresh());
        utilities.addView(interrupt, weight());
        utilities.addView(refresh, weightWithStart());
        sessionCard.addView(utilities, top(8));
        root.addView(sessionCard, top(8));

        root.addView(eyebrow(getString(R.string.voice_transcript_title)), top(25));
        LinearLayout transcriptCard = card();
        finalTranscript = new TextView(requireContext());
        finalTranscript.setTextColor(color(R.color.matrix_text));
        finalTranscript.setTextSize(16);
        finalTranscript.setLineSpacing(dp(3), 1f);
        finalTranscript.setTextIsSelectable(true);
        transcriptCard.addView(finalTranscript);
        partialTranscript = new TextView(requireContext());
        partialTranscript.setTextColor(color(R.color.matrix_primary));
        partialTranscript.setTextSize(15);
        partialTranscript.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.ITALIC));
        partialTranscript.setLineSpacing(dp(3), 1f);
        transcriptCard.addView(partialTranscript, top(12));
        root.addView(transcriptCard, top(8));

        root.addView(eyebrow(getString(R.string.voice_models_title)), top(25));
        root.addView(copy(getString(R.string.voice_models_intro)), top(6));
        modelNotice = copy("");
        modelNotice.setTextColor(color(R.color.matrix_primary_dark));
        modelNotice.setVisibility(View.GONE);
        root.addView(modelNotice, top(9));

        TextView transfersTitle = sectionLabel(R.string.voice_models_transfers);
        root.addView(transfersTitle, top(14));
        transfers = new LinearLayout(requireContext());
        transfers.setOrientation(LinearLayout.VERTICAL);
        root.addView(transfers, top(7));

        TextView installedTitle = sectionLabel(R.string.voice_models_installed);
        root.addView(installedTitle, top(18));
        installedModels = new LinearLayout(requireContext());
        installedModels.setOrientation(LinearLayout.VERTICAL);
        root.addView(installedModels, top(7));

        viewModel.state().observe(getViewLifecycleOwner(), this::render);
        new ViewModelProvider(requireActivity(), activity().viewModelFactory())
                .get(LauncherViewModel.class).connectionState().observe(getViewLifecycleOwner(), value -> {
                    connected = viewModel.isHostConnected();
                    if (connected) viewModel.startObserving();
                    renderControls();
                });
        return scroll;
    }

    private void render(@NonNull VoiceViewModel.State value) {
        rendered = value;
        status.setText(statusText(value));
        guidance.setText(value.phase == VoiceViewModel.Phase.WAKING
                ? R.string.voice_session_preparing_guidance : R.string.voice_session_guidance);
        finalTranscript.setText(value.finalText.isEmpty() ? getString(R.string.voice_transcript_hint)
                : getString(R.string.voice_final_prefix) + "\n" + value.finalText);
        finalTranscript.setTextColor(color(value.finalText.isEmpty()
                ? R.color.matrix_muted : R.color.matrix_text));
        partialTranscript.setText(value.partialText);
        partialTranscript.setVisibility(value.partialText.isEmpty() ? View.GONE : View.VISIBLE);
        renderModels(value);
        renderControls();
    }

    /** Ask before the first potentially metered, one-time offline-model download. */
    private void beginVoiceSession() {
        SharedPreferences preferences = requireContext().getSharedPreferences(
                VOICE_PREFERENCES, android.content.Context.MODE_PRIVATE);
        if (preferences.getBoolean(MODEL_DOWNLOAD_EXPLAINED, false)) {
            viewModel.startSession(Locale.getDefault().toLanguageTag());
            return;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.voice_model_download_title)
                .setMessage(R.string.voice_model_download_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.voice_model_download_continue, (dialog, which) -> {
                    preferences.edit().putBoolean(MODEL_DOWNLOAD_EXPLAINED, true).apply();
                    viewModel.startSession(Locale.getDefault().toLanguageTag());
                })
                .show();
    }

    private String statusText(@NonNull VoiceViewModel.State value) {
        switch (value.phase) {
            case DISABLED: return getString(R.string.voice_status_disabled);
            case READY: return getString(R.string.voice_status_ready);
            case WAKING: return getString(R.string.voice_status_waking);
            case RECORDING: return getString(R.string.voice_status_recording);
            case FINISHING: return getString(R.string.voice_status_finished);
            case WORKING: return getString(R.string.voice_status_working);
            case RESPONDING: return getString(R.string.voice_status_responding);
            case INTERRUPTING: return getString(R.string.voice_status_interrupting);
            case INTERRUPTED: return getString(R.string.voice_status_interrupted);
            case FINISHED: return getString(R.string.voice_status_finished);
            case ERROR: return getString(R.string.voice_status_error, value.errorCode);
            case HOST_UNAVAILABLE:
            default: return getString(R.string.voice_status_host_unavailable);
        }
    }

    private void renderControls() {
        VoiceViewModel.State value = rendered;
        boolean active = value != null && value.isActive();
        boolean recording = value != null && value.phase == VoiceViewModel.Phase.RECORDING;
        start.setEnabled(connected && value != null && value.enabled && !active);
        finish.setEnabled(connected && recording);
        interrupt.setEnabled(connected && active && value.phase != VoiceViewModel.Phase.INTERRUPTING);
        refresh.setEnabled(connected);
    }

    private void renderModels(@NonNull VoiceViewModel.State value) {
        transfers.removeAllViews();
        installedModels.removeAllViews();
        List<ModelDownloadInfo> active = new ArrayList<>();
        List<ModelDownloadInfo> library = new ArrayList<>();
        List<ModelDownloadInfo> pending = new ArrayList<>();
        for (ModelDownloadInfo model : value.models) {
            if (model.state == ModelDownloadInfo.DOWNLOAD_STATE_DOWNLOADING
                    || model.state == ModelDownloadInfo.DOWNLOAD_STATE_PAUSED
                    || model.state == ModelDownloadInfo.DOWNLOAD_STATE_FAILED) active.add(model);
            else if (model.state == ModelDownloadInfo.DOWNLOAD_STATE_COMPLETED) library.add(model);
            else pending.add(model);
        }
        if (active.isEmpty()) {
            transfers.addView(modelEmpty(R.string.voice_models_transfers_empty));
        } else {
            for (ModelDownloadInfo model : active) addSpaced(transfers, modelCard(model, value));
        }
        if (!pending.isEmpty()) {
            Button install = button(R.string.voice_models_install, true,
                    ignored -> viewModel.installOfflineModels());
            transfers.addView(install, top(8));
        }
        if (!library.isEmpty()) {
            for (ModelDownloadInfo model : library) addSpaced(installedModels, modelCard(model, value));
        } else {
            installedModels.addView(modelEmpty(R.string.voice_models_installed_empty));
        }
        for (ModelDownloadInfo model : pending) addSpaced(installedModels, modelCard(model, value));

        if (value.deletingModelId != null) {
            modelNotice.setText(getString(R.string.voice_models_deleting, modelName(value.deletingModelId)));
            modelNotice.setVisibility(View.VISIBLE);
        } else if (value.modelOperationError != MatrixErrorCode.SUCCESS) {
            modelNotice.setText(getString(R.string.voice_models_delete_failed, value.modelOperationError));
            modelNotice.setVisibility(View.VISIBLE);
        } else {
            modelNotice.setVisibility(View.GONE);
        }
    }

    private View modelCard(@NonNull ModelDownloadInfo model, @NonNull VoiceViewModel.State state) {
        LinearLayout value = card();
        TextView title = new TextView(requireContext());
        title.setText(modelName(model.modelId));
        title.setTextColor(color(R.color.matrix_text));
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        value.addView(title);
        TextView version = copy(getString(R.string.voice_model_version, modelVersion(model.modelId)));
        value.addView(version, top(3));

        if (model.state == ModelDownloadInfo.DOWNLOAD_STATE_COMPLETED) {
            TextView detail = copy(getString(R.string.voice_model_installed_detail,
                    formatBytes(model.bytesDownloaded)));
            detail.setTextColor(color(R.color.matrix_primary_dark));
            value.addView(detail, top(10));
            Button delete = button(R.string.delete, false, ignored -> confirmDelete(model));
            delete.setEnabled(state.deletingModelId == null);
            value.addView(delete, top(12));
        } else if (model.state == ModelDownloadInfo.DOWNLOAD_STATE_DOWNLOADING
                || model.state == ModelDownloadInfo.DOWNLOAD_STATE_PAUSED
                || model.state == ModelDownloadInfo.DOWNLOAD_STATE_FAILED) {
            int progress = percentage(model.bytesDownloaded, model.bytesTotal);
            TextView detail = copy(progressDescription(model, progress));
            value.addView(detail, top(11));
            ProgressBar bar = new ProgressBar(requireContext(), null,
                    android.R.attr.progressBarStyleHorizontal);
            bar.setIndeterminate(model.bytesTotal <= 0L);
            if (model.bytesTotal > 0L) {
                bar.setMax(1000);
                bar.setProgress(progress * 10);
            }
            value.addView(bar, top(9));
        } else {
            TextView detail = copy(getString(R.string.voice_model_not_installed_detail));
            value.addView(detail, top(10));
        }
        return value;
    }

    private void confirmDelete(@NonNull ModelDownloadInfo model) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.voice_models_delete_title)
                .setMessage(getString(R.string.voice_models_delete_message, modelName(model.modelId),
                        formatBytes(model.bytesDownloaded)))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete,
                        (dialog, which) -> viewModel.deleteOfflineModel(model.modelId))
                .show();
    }

    private TextView sectionLabel(int text) {
        TextView value = new TextView(requireContext());
        value.setText(text);
        value.setTextColor(color(R.color.matrix_text));
        value.setTextSize(16);
        value.setTypeface(Typeface.DEFAULT_BOLD);
        return value;
    }

    private TextView modelEmpty(int text) {
        TextView value = copy(getString(text));
        value.setBackgroundResource(R.drawable.bg_card);
        value.setPadding(dp(14), dp(13), dp(14), dp(13));
        return value;
    }

    private void addSpaced(@NonNull LinearLayout parent, @NonNull View child) {
        parent.addView(child, parent.getChildCount() == 0 ? new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) : top(8));
    }

    private String progressDescription(@NonNull ModelDownloadInfo model, int progress) {
        if (model.state == ModelDownloadInfo.DOWNLOAD_STATE_PAUSED) {
            return getString(R.string.voice_model_paused_detail, formatBytes(model.bytesDownloaded));
        }
        if (model.state == ModelDownloadInfo.DOWNLOAD_STATE_FAILED) {
            return getString(R.string.voice_model_failed_detail, model.errorCode);
        }
        if (model.bytesTotal <= 0L) return getString(R.string.voice_model_downloading_unknown);
        return getString(R.string.voice_model_downloading_detail, formatBytes(model.bytesDownloaded),
                formatBytes(model.bytesTotal), progress);
    }

    private static int percentage(long downloaded, long total) {
        if (total <= 0L) return 0;
        return (int) Math.min(100L, Math.max(0L, downloaded * 100L / total));
    }

    private String modelName(@Nullable String id) {
        if ("vosk-en".equals(id)) return getString(R.string.voice_model_english);
        if ("vosk-cn".equals(id)) return getString(R.string.voice_model_chinese);
        return id == null ? getString(R.string.voice_model_unknown) : id;
    }

    private String modelVersion(@Nullable String id) {
        return "vosk-en".equals(id) ? "0.15" : "vosk-cn".equals(id) ? "0.22" : "—";
    }

    private String formatBytes(long bytes) {
        if (bytes <= 0L) return "0 MB";
        return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024f * 1024f));
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setPadding(dp(16), dp(15), dp(16), dp(15));
        return card;
    }

    private TextView eyebrow(String text) {
        TextView value = new TextView(requireContext());
        value.setText(getString(R.string.agent_eyebrow_format, text));
        value.setTextColor(color(R.color.matrix_accent));
        value.setTextSize(11);
        value.setTypeface(Typeface.DEFAULT_BOLD);
        return value;
    }

    private TextView heading(String text) {
        TextView value = new TextView(requireContext());
        value.setText(text);
        value.setTextColor(color(R.color.matrix_text));
        value.setTextSize(31);
        value.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        return value;
    }

    private TextView copy(String text) {
        TextView value = new TextView(requireContext());
        value.setText(text);
        value.setTextColor(color(R.color.matrix_muted));
        value.setTextSize(14);
        value.setLineSpacing(dp(3), 1f);
        return value;
    }

    private Button button(int text, boolean primary, View.OnClickListener listener) {
        Button value = new Button(requireContext());
        value.setText(text);
        value.setTextSize(14);
        value.setTypeface(Typeface.DEFAULT_BOLD);
        value.setBackgroundResource(primary ? R.drawable.bg_primary : R.drawable.bg_outline);
        value.setTextColor(color(primary ? android.R.color.white : R.color.matrix_primary_dark));
        value.setOnClickListener(listener);
        return value;
    }

    private LinearLayout.LayoutParams top(int margin) {
        LinearLayout.LayoutParams value = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        value.topMargin = dp(margin);
        return value;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, dp(44), 1f);
    }

    private LinearLayout.LayoutParams weightWithStart() {
        LinearLayout.LayoutParams value = weight();
        value.leftMargin = dp(8);
        return value;
    }

    private int color(int resource) { return ContextCompat.getColor(requireContext(), resource); }
    private int dp(int value) { return activity().dp(value); }
    private LauncherActivity activity() { return (LauncherActivity) requireActivity(); }
}
