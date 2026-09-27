package com.matrix.agent.launcher.presentation;

import android.animation.ObjectAnimator;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.matrix.agent.api.conversation.ConversationMessage;
import com.matrix.agent.api.conversation.ConversationRuntimeStage;
import com.matrix.agent.api.debug.DebugTraceWireEvent;
import com.matrix.agent.launcher.BuildConfig;
import com.matrix.agent.launcher.LauncherActivity;
import com.matrix.agent.launcher.R;

import java.util.List;
import java.util.Locale;

/**
 * 对话页。纯渲染：全部状态来自 ViewModel 的不可变投影；
 * EXECUTION_UNKNOWN 按契约渲染为“执行结果未知”提示行，绝不显示成取消或失败。
 *
 * <p>输入区（输入交互增强 Phase 1）：多行输入 + 全屏编辑 + Host 加密草稿、
 * 统一提交（发送/追加由 Host 原子判定）、固定 PTT 次级钮（按压反馈契约）与
 * Host 驱动的运行阶段条。</p>
 */
public final class ConversationFragment extends Fragment {

    private static final int MAX_RENDERED_ROWS = 200;
    /** “回车发送”偏好（I4 §7.3）：纯 Launcher UI 偏好，默认关闭。 */
    private static final String INPUT_PREFERENCES = "conversation_input_preferences";
    private static final String KEY_ENTER_TO_SEND = "enter_to_send";

    private LinearLayout messageRows;
    private TextView notice;
    private EditText input;
    private TextView cancelButton;
    private android.widget.ImageButton voiceButton;
    /** I5 只读模型胶囊：点击弹只读简表；数据来自 ModelRuntimeStatus。 */
    private TextView modelCapsule;
    /** 调节图标与模型胶囊都只打开只读模型简表。 */
    private View modelOptionsButton;
    /** I6 附件 chips 容器与 + 入口。 */
    private LinearLayout attachmentChipRow;
    private View attachmentChipsScroll;
    private View attachButton;
    private TextView stageView;
    private View fullscreenButton;
    private TextView dynamicTitle;
    private TextView summaryBadge;
    /** PTT 状态条（§5.2 可视化）：呼吸点 + 阶段标签 + 实时转写；IDLE 隐藏。 */
    private LinearLayout pttStatusBar;
    private View pttDot;
    private TextView pttLabel;
    private TextView pttTranscript;
    private ObjectAnimator pttPulse;
    private ConversationViewModel viewModel;
    private int renderedCount;
    /** 上一次重建入列的消息快照：用于把本次变化归类为前插/追加/重载，驱动滚动策略。 */
    private List<ConversationViewModel.UiMessage> renderedSnapshot = List.of();
    /** 草稿恢复应用中：抑制 TextWatcher 回灌（恢复不该触发一次多余的 debounce 保存）。 */
    private boolean applyingDraftRestore;
    /** PTT 手势进行中（触摸层事实）：与 ViewModel 阶段机正交，只补 DOWN 即时按压视觉。 */
    private boolean pttHeld;
    private android.widget.Button restoreOverlayDraft;
    /** 主动作的触摸契约在一个手势内不可改写；否则快速点按会丢失 ACTION_UP。 */
    private boolean primaryActionConfigured;
    private boolean primaryActionIsSend;
    /** 附件 picker（SAF）：只收 text/*（图片入口在 OCR 选型前不开放，§9.4）。 */
    private final androidx.activity.result.ActivityResultLauncher<String[]> attachmentPicker =
            registerForActivityResult(
                    new androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
                    uri -> {
                        if (uri != null) onAttachmentPicked(uri);
                    });

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup parent, @Nullable Bundle state) {
        LauncherActivity activity = activity();
        View root = inflater.inflate(R.layout.fragment_conversation, parent, false);
        viewModel = new ViewModelProvider(requireActivity(), activity.viewModelFactory())
                .get(ConversationViewModel.class);
        messageRows = root.findViewById(R.id.conversation_messages);
        notice = root.findViewById(R.id.conversation_notice);
        input = root.findViewById(R.id.conversation_input);
        cancelButton = root.findViewById(R.id.conversation_cancel);
        voiceButton = root.findViewById(R.id.conversation_voice);
        modelCapsule = root.findViewById(R.id.conversation_model_capsule);
        modelOptionsButton = root.findViewById(R.id.conversation_model_options);
        attachmentChipRow = root.findViewById(R.id.conversation_attachment_chip_row);
        attachmentChipsScroll = root.findViewById(R.id.conversation_attachment_chips);
        attachButton = root.findViewById(R.id.conversation_attach);
        stageView = root.findViewById(R.id.conversation_stage);
        fullscreenButton = root.findViewById(R.id.conversation_fullscreen);
        dynamicTitle = root.findViewById(R.id.conversation_dynamic_title);
        summaryBadge = root.findViewById(R.id.conversation_summary_badge);
        pttStatusBar = root.findViewById(R.id.conversation_ptt_status);
        pttDot = root.findViewById(R.id.conversation_ptt_dot);
        pttLabel = root.findViewById(R.id.conversation_ptt_label);
        pttTranscript = root.findViewById(R.id.conversation_ptt_transcript);
        dynamicTitle.setOnLongClickListener(ignored -> {
            promptRename();
            return true;
        });

        fullscreenButton.setOnClickListener(ignored -> showFullscreenEditor());
        modelOptionsButton.setOnClickListener(ignored -> showModelCapsuleDialog());
        attachButton.setOnClickListener(ignored -> attachmentPicker.launch(
                new String[] {"text/*"}));
        applyEnterToSendPreference();
        input.setOnEditorActionListener((view, actionId, event) -> {
            if (!isEnterToSendEnabled()) return false;
            boolean imeSend = actionId == EditorInfo.IME_ACTION_SEND;
            boolean hardwareEnter = event != null
                    && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (imeSend || hardwareEnter) {
                submitInput();
                return true;
            }
            return false;
        });
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count,
                    int after) { }

            @Override public void onTextChanged(CharSequence s, int start, int before,
                    int count) { }

            @Override public void afterTextChanged(Editable s) {
                if (applyingDraftRestore) return;
                viewModel.onDraftChanged(s.toString(), input.getSelectionStart(),
                        input.getSelectionEnd());
                renderInputControls();
            }
        });
        input.setOnFocusChangeListener((view, focused) -> {
            if (!focused) viewModel.flushDraft();
        });
        cancelButton.setOnClickListener(ignored -> viewModel.cancelLatest());
        stageView.setOnClickListener(ignored -> scrollToStageTask());
        root.findViewById(R.id.conversation_scroll).setOnClickListener(
                ignored -> viewModel.refresh());

        viewModel.state().observe(getViewLifecycleOwner(), this::render);
        viewModel.draftRestores().observe(getViewLifecycleOwner(), this::applyDraftRestore);
        new ViewModelProvider(requireActivity(), activity.viewModelFactory())
                .get(LauncherViewModel.class)
                .connectionState().observe(getViewLifecycleOwner(), value -> {
                    if (viewModel.isHostConnected()) {
                        viewModel.start();
                        viewModel.refreshModelRuntime();
                    }
                });
        restoreOverlayDraft = new android.widget.Button(requireContext());
        restoreOverlayDraft.setText("恢复小窗草稿");
        restoreOverlayDraft.setVisibility(View.GONE);
        restoreOverlayDraft.setOnClickListener(ignored -> {
            var stateValue = viewModel.state().getValue();
            if (stateValue == null || stateValue.conversationId == null) return;
            var store = launcherApplication().overlay().drafts();
            var draft = store.get(stateValue.conversationId);
            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("小窗草稿")
                    .setMessage(draft.text())
                    .setPositiveButton("追加到输入框", (dialog, which) -> {
                        String current = input.getText().toString();
                        input.setText(current.isBlank() ? draft.text() : current + "\n" + draft.text());
                        input.setSelection(input.length());
                        store.clearIfRevision(stateValue.conversationId, draft.revision());
                        restoreOverlayDraft.setVisibility(View.GONE);
                    }).setNegativeButton("保留草稿", null).show();
        });
        ((android.widget.LinearLayout) root.findViewById(R.id.conversation_composer_card))
                .addView(restoreOverlayDraft, 0);
        return root;
    }

    private com.matrix.agent.launcher.LauncherApplication launcherApplication() {
        return (com.matrix.agent.launcher.LauncherApplication) requireActivity().getApplication();
    }

    @Override public void onResume() {
        super.onResume();
        var value = viewModel.state().getValue();
        launcherApplication().overlay().conversationPageVisible(value == null ? null : value.conversationId);
    }

    @Override public void onPause() {
        launcherApplication().overlay().conversationPageVisible(null);
        super.onPause();
    }

    @Override public void onStart() {
        super.onStart();
        setConversationNavigationSurface(true);
        if (viewModel.isHostConnected()) {
            viewModel.start();
            // 胶囊是“下一次提交将使用的当前 Host 模型”，页面重新可见时必须重读 Host
            // 真相，不能沿用上次进入页面的缓存。
            viewModel.refreshModelRuntime();
        }
    }

    @Override public void onStop() {
        // 草稿契约（§7.2）：onStop 立即 flush——debounce 保存与提交经 lane 全序。
        viewModel.flushDraft();
        viewModel.closeSubscription();
        setConversationNavigationSurface(false);
        super.onStop();
    }

    /** 输入面贴底延续到手势导航区；离开会话页后恢复应用的深色全局 chrome。 */
    private void setConversationNavigationSurface(boolean active) {
        android.view.Window window = requireActivity().getWindow();
        int color = ContextCompat.getColor(requireContext(), active
                ? R.color.composer_surface : R.color.matrix_primary_dark);
        window.setNavigationBarColor(color);
        int visibility = window.getDecorView().getSystemUiVisibility();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            int flag = View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            window.getDecorView().setSystemUiVisibility(active
                    ? visibility | flag : visibility & ~flag);
        }
    }

    /** PTT 按压契约（§5.2）：DOWN 立即 pressed 视觉；UP=flush；CANCEL=取消本轮。 */
    private boolean onPttTouch(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                pttHeld = true;
                voiceButton.setPressed(true);
                viewModel.startRecording();
                renderPttVisual();
                return true;
            case MotionEvent.ACTION_UP:
                if (!pttHeld) return true;
                pttHeld = false;
                voiceButton.setPressed(false);
                viewModel.stopRecording();
                renderPttVisual();
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (!pttHeld) return true;
                pttHeld = false;
                voiceButton.setPressed(false);
                viewModel.cancelRecording();
                renderPttVisual();
                return true;
            default:
                return true;
        }
    }

    /**
     * 按钮视觉三态（§5.2）：默认“按住说话” → 按住“松开发送”（pressed 加深）→
     * Host capture-started“录音中…”（selected 转暖橙 accent）。阶段唯一来自
     * ViewModel 投影；pttHeld 只补 postValue 异步派发前一帧的即时按压反馈。
     */
    private void renderPttVisual() {
        if (hasSubmittableInput()) return;
        ConversationViewModel.State state = viewModel.state().getValue();
        ConversationViewModel.PttPhase phase = state == null
                ? ConversationViewModel.PttPhase.IDLE : state.pttPhase;
        voiceButton.setSelected(phase == ConversationViewModel.PttPhase.LISTENING);
        voiceButton.setContentDescription(getString(switch (phase) {
            case LISTENING -> R.string.conversation_voice_listening;
            case PROCESSING -> R.string.conversation_voice_processing;
            default -> pttHeld ? R.string.conversation_voice_recording
                    : R.string.conversation_voice_hold;
        }));
    }

    /** PTT 状态条（§5.2 可视化补全）：按下即现——阶段标签 + 实时转写；IDLE 隐藏。 */
    private void renderPttStatus(ConversationViewModel.State value) {
        ConversationViewModel.PttPhase phase = value.pttPhase;
        if (phase == ConversationViewModel.PttPhase.IDLE) {
            pttStatusBar.setVisibility(View.GONE);
            setPttPulse(false);
            return;
        }
        pttStatusBar.setVisibility(View.VISIBLE);
        pttLabel.setText(switch (phase) {
            case ARMING -> R.string.conversation_voice_starting;
            case LISTENING -> R.string.conversation_voice_listening;
            default -> R.string.conversation_voice_processing; // IDLE 已提前返回
        });
        String transcript = value.liveTranscript;
        boolean hasTranscript = transcript != null && !transcript.isBlank();
        pttTranscript.setVisibility(hasTranscript ? View.VISIBLE : View.GONE);
        if (hasTranscript) pttTranscript.setText(transcript);
        setPttPulse(phase == ConversationViewModel.PttPhase.LISTENING);
    }

    /** “录音中”呼吸点：LISTENING 期间循环渐隐；离开该阶段即停并复位不透明度。 */
    private void setPttPulse(boolean recording) {
        boolean pulsing = pttPulse != null && pttPulse.isRunning();
        if (recording == pulsing) return;
        if (recording) {
            if (pttPulse == null) {
                pttPulse = ObjectAnimator.ofFloat(pttDot, View.ALPHA, 1f, 0.25f);
                pttPulse.setDuration(650L);
                pttPulse.setRepeatCount(ObjectAnimator.INFINITE);
                pttPulse.setRepeatMode(ObjectAnimator.REVERSE);
            }
            pttPulse.start();
        } else {
            pttPulse.cancel();
            pttDot.setAlpha(1f);
        }
    }

    @Override public void onDestroyView() {
        if (pttPulse != null) pttPulse.cancel();
        pttPulse = null;
        super.onDestroyView();
    }

    private boolean isEnterToSendEnabled() {
        return requireContext().getSharedPreferences(INPUT_PREFERENCES,
                android.content.Context.MODE_PRIVATE)
                .getBoolean(KEY_ENTER_TO_SEND, false);
    }

    private void applyEnterToSendPreference() {
        boolean enabled = isEnterToSendEnabled();
        input.setImeOptions(enabled
                ? EditorInfo.IME_ACTION_SEND
                : EditorInfo.IME_FLAG_NO_ENTER_ACTION | EditorInfo.IME_ACTION_NONE);
    }

    /** 全屏编辑（I1）：只编辑并回填草稿，绝不提交；含“回车发送”偏好开关。 */
    private void showFullscreenEditor() {
        EditText editor = new EditText(requireContext());
        editor.setText(input.getText().toString());
        editor.setSelection(input.getSelectionStart(), input.getSelectionEnd());
        editor.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        editor.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        editor.setTextSize(16);
        editor.setMinLines(8);
        editor.setTextColor(ContextCompat.getColor(requireContext(), R.color.matrix_text));
        android.widget.CheckBox enterToSend = new android.widget.CheckBox(requireContext());
        enterToSend.setText(R.string.conversation_enter_to_send);
        enterToSend.setChecked(isEnterToSendEnabled());
        enterToSend.setOnCheckedChangeListener((button, checked) -> {
            requireContext().getSharedPreferences(INPUT_PREFERENCES,
                    android.content.Context.MODE_PRIVATE).edit()
                    .putBoolean(KEY_ENTER_TO_SEND, checked).apply();
            applyEnterToSendPreference();
        });
        LinearLayout form = new LinearLayout(requireContext());
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        form.setPadding(pad, pad, pad, dp(8));
        form.addView(editor, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        TextView attachmentContext = new TextView(requireContext());
        attachmentContext.setText(fullscreenAttachmentSummary());
        attachmentContext.setTextColor(ContextCompat.getColor(requireContext(),
                R.color.matrix_muted));
        attachmentContext.setTextSize(12);
        attachmentContext.setPadding(0, dp(12), 0, dp(4));
        if (!attachmentContext.getText().toString().isBlank()) {
            form.addView(attachmentContext);
        }
        form.addView(enterToSend);
        androidx.appcompat.app.AlertDialog dialog =
                new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                        .setTitle(R.string.conversation_fullscreen_input)
                        .setView(form)
                        .setPositiveButton(R.string.conversation_fullscreen_done, null)
                        .setNegativeButton(R.string.cancel, null)
                        .create();
        // 写回草稿（不提交）：只有“完成”明确确认才回填；取消/返回保持原草稿。
        java.util.function.BiConsumer<String, int[]> writeBack = (text, selection) -> {
            applyingDraftRestore = true;
            int start = Math.max(0, Math.min(selection[0], text.length()));
            int end = Math.max(start, Math.min(selection[1], text.length()));
            input.setText(text);
            input.setSelection(start, end);
            applyingDraftRestore = false;
            viewModel.onDraftChanged(text, start, end);
            renderInputControls();
        };
        final int[] selection = {editor.getSelectionStart(), editor.getSelectionEnd()};
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count,
                    int after) { }

            @Override public void onTextChanged(CharSequence s, int start, int before,
                    int count) { }

            @Override public void afterTextChanged(Editable s) {
                selection[0] = editor.getSelectionStart();
                selection[1] = editor.getSelectionEnd();
            }
        });
        dialog.show();
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(ignored -> {
                    writeBack.accept(editor.getText().toString(), selection);
                    dialog.dismiss();
                });
    }

    /** 草稿恢复：应用到输入框（含光标），不等值覆盖避免 IME 跳动。 */
    private void applyDraftRestore(ConversationViewModel.DraftRestore restore) {
        if (restore == null) return;
        applyingDraftRestore = true;
        if (!input.getText().toString().equals(restore.text())) {
            input.setText(restore.text());
        }
        int start = Math.max(0, Math.min(restore.selectionStart(), restore.text().length()));
        int end = Math.max(start, Math.min(restore.selectionEnd(), restore.text().length()));
        input.setSelection(start, end);
        applyingDraftRestore = false;
        renderInputControls();
    }

    private void submitInput() {
        String text = input.getText().toString();
        ConversationViewModel.State state = viewModel.state().getValue();
        boolean hasReadyAttachment = state != null && state.draftAttachments.stream()
                .anyMatch(com.matrix.agent.api.conversation.ConversationAttachment::isReady);
        if (text.isBlank() && !hasReadyAttachment) return;
        // 统一提交会在 ViewModel 中冻结“本次草稿快照”、轮换新 instance 后再清空编辑器。
        // 不能在此先 setText("")：那会把本次提交错误地变成一个空草稿保存生命周期。
        viewModel.submitUnified(text);
    }

    private boolean currentStateHasRunning() {
        ConversationViewModel.State state = viewModel.state().getValue();
        return state != null && state.hasRunningTask;
    }

    private void render(@NonNull ConversationViewModel.State value) {
        if (isResumed()) launcherApplication().overlay().conversationPageVisible(value.conversationId);
        if (restoreOverlayDraft != null) restoreOverlayDraft.setVisibility(value.conversationId != null
                && !launcherApplication().overlay().drafts().get(value.conversationId).text().isBlank()
                ? View.VISIBLE : View.GONE);
        renderNotice(value);
        renderMessages(value.messages);
        renderInputBar(value);
        renderPttStatus(value);
        if (value.conversationTitle != null) {
            dynamicTitle.setText(value.conversationTitle);
        }
        summaryBadge.setVisibility(value.summaryActive ? View.VISIBLE : View.GONE);
    }

    /** 底栏（§3.1/§5.2）：阶段条 + 文字次级取消 + 固定 PTT + 主动作（发送/追加）。 */
    private void renderInputBar(@NonNull ConversationViewModel.State value) {
        ConversationRuntimeStage stage = value.runtimeStage;
        if (stage != null) {
            stageView.setText(stageText(stage));
            stageView.setVisibility(View.VISIBLE);
        } else {
            stageView.setVisibility(View.GONE);
        }
        cancelButton.setVisibility(value.hasRunningTask ? View.VISIBLE : View.GONE);
        renderModelCapsule(value);
        renderAttachmentChips(value);
        renderInputControls();
        voiceButton.setEnabled(!value.sending && value.conversationId != null);
        renderPttVisual();
    }

    /** I5 胶囊投影：模型名 · 后端（未配置/未就绪各自浅色态，绝不统称 Connecting）。 */
    private void renderModelCapsule(@NonNull ConversationViewModel.State value) {
        com.matrix.agent.api.model.ModelRuntimeStatus runtime = value.modelRuntime;
        if (runtime == null || !runtime.ready) {
            modelCapsule.setText(R.string.conversation_model_unconfigured);
            modelCapsule.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.matrix_muted));
        } else {
            String backend = runtime.backend
                    == com.matrix.agent.api.model.ModelRuntimeStatus.BACKEND_ON_DEVICE
                    ? getString(R.string.conversation_model_backend_device)
                    : getString(R.string.conversation_model_backend_cloud);
            modelCapsule.setText(getString(R.string.conversation_model_format,
                    runtime.activeModelId == null ? "—" : runtime.activeModelId, backend));
            modelCapsule.setTextColor(
                    ContextCompat.getColor(requireContext(), R.color.matrix_primary_dark));
        }
    }

    /** 胶囊点击 → 只读简表 + 打开模型接入页入口（§8.1：不在这里编辑配置）。 */
    private void showModelCapsuleDialog() {
        ConversationViewModel.State state = viewModel.state().getValue();
        com.matrix.agent.api.model.ModelRuntimeStatus runtime =
                state == null ? null : state.modelRuntime;
        String backend = runtime == null
                ? getString(R.string.value_unavailable)
                : runtime.backend
                        == com.matrix.agent.api.model.ModelRuntimeStatus.BACKEND_ON_DEVICE
                        ? getString(R.string.conversation_model_backend_device)
                        : runtime.backend
                                == com.matrix.agent.api.model.ModelRuntimeStatus.BACKEND_CLOUD
                                ? getString(R.string.conversation_model_backend_cloud)
                                : getString(R.string.value_unavailable);
        String body = getString(R.string.conversation_model_dialog_body,
                backend,
                runtime == null || runtime.activeModelId == null
                        ? getString(R.string.value_unavailable) : runtime.activeModelId,
                runtime != null && runtime.ready);
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.conversation_model_dialog_title)
                .setMessage(body)
                .setPositiveButton(R.string.conversation_model_dialog_go_models,
                        (dialog, which) -> activity().showModelsPage())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    /** I6 chips：每项可见、可删、可解释（FAILED chip 说明摄取失败原因）。 */
    private void renderAttachmentChips(@NonNull ConversationViewModel.State value) {
        List<com.matrix.agent.api.conversation.ConversationAttachment> attachments =
                value.draftAttachments;
        attachmentChipRow.removeAllViews();
        if (attachments.isEmpty()) {
            attachmentChipsScroll.setVisibility(View.GONE);
            return;
        }
        attachmentChipsScroll.setVisibility(View.VISIBLE);
        for (com.matrix.agent.api.conversation.ConversationAttachment attachment
                : attachments) {
            attachmentChipRow.addView(buildAttachmentChip(attachment));
        }
    }

    private View buildAttachmentChip(
            com.matrix.agent.api.conversation.ConversationAttachment attachment) {
        LinearLayout chip = new LinearLayout(requireContext());
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(android.view.Gravity.CENTER_VERTICAL);
        chip.setBackgroundResource(R.drawable.bg_attachment_chip);
        chip.setPadding(dp(10), dp(5), dp(4), dp(5));
        String label = attachment.isReady() ? attachment.safeDisplayName
                : attachment.isStaging()
                        ? getString(R.string.conversation_attachment_staging_chip,
                                attachment.safeDisplayName)
                        : getString(R.string.conversation_attachment_failed_chip,
                                attachment.safeDisplayName);
        TextView name = new TextView(requireContext());
        name.setText(label);
        name.setTextColor(ContextCompat.getColor(requireContext(), R.color.matrix_text));
        name.setTextSize(12);
        name.setMaxWidth(dp(180));
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        chip.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        android.widget.ImageButton remove = new android.widget.ImageButton(requireContext());
        remove.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        remove.setBackground(null);
        remove.setPadding(dp(4), dp(4), dp(4), dp(4));
        remove.setContentDescription(getString(R.string.conversation_attachment_delete));
        LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(dp(28), dp(28));
        removeParams.setMarginStart(dp(4));
        ConversationViewModel.State state = viewModel.state().getValue();
        remove.setEnabled(state == null || !state.sending);
        remove.setOnClickListener(ignored -> viewModel.deleteAttachment(attachment.attachmentId));
        chip.addView(remove, removeParams);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd(dp(6));
        chip.setLayoutParams(params);
        return chip;
    }

    /** SAF 回调：打开只读 PFD 交给 Host 摄取（URI 授权不跨 UID 假定，§9.2）。 */
    private void onAttachmentPicked(android.net.Uri uri) {
        try {
            android.os.ParcelFileDescriptor fd =
                    requireContext().getContentResolver().openFileDescriptor(uri, "r");
            if (fd == null) {
                android.widget.Toast.makeText(requireContext(),
                        R.string.conversation_attachment_error_host,
                        android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            String name = queryDisplayName(uri);
            viewModel.stageAttachment(fd, mimeOf(name), name, noticeText ->
                    android.widget.Toast.makeText(requireContext(), noticeText,
                            android.widget.Toast.LENGTH_SHORT).show());
        } catch (java.io.FileNotFoundException | SecurityException unavailable) {
            android.widget.Toast.makeText(requireContext(),
                    R.string.conversation_attachment_error_host,
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private String queryDisplayName(android.net.Uri uri) {
        try (android.database.Cursor cursor = requireContext().getContentResolver().query(
                uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(
                        android.provider.OpenableColumns.DISPLAY_NAME);
                if (index >= 0) return cursor.getString(index);
            }
        } catch (RuntimeException ignored) {
            // 名字只是展示；查询失败用 URI 尾段兜底。
        }
        String path = uri.getLastPathSegment();
        return path == null ? "附件" : path.substring(path.lastIndexOf('/') + 1);
    }

    private static String mimeOf(String displayName) {
        if (displayName == null) return "text/plain";
        int dot = displayName.lastIndexOf('.');
        if (dot < 0) return "text/plain";
        return switch (displayName.substring(dot + 1).toLowerCase(Locale.ROOT)) {
            case "md", "markdown" -> "text/markdown";
            case "json" -> "application/json";
            case "xml" -> "application/xml";
            case "csv" -> "text/csv";
            case "html", "htm" -> "text/html";
            default -> "text/plain";
        };
    }

    /** 主动作可见性由草稿内容驱动（Fragment 本地事实），标签由运行态驱动。 */
    private void renderInputControls() {
        ConversationViewModel.State state = viewModel.state().getValue();
        boolean hasSubmittableInput = hasSubmittableInput();
        boolean editingEnabled = state == null || !state.sending;
        input.setEnabled(editingEnabled);
        fullscreenButton.setEnabled(editingEnabled);
        attachButton.setEnabled(editingEnabled);
        voiceButton.setEnabled(editingEnabled && state != null && state.conversationId != null);
        if (hasSubmittableInput) {
            // 文字/READY 附件出现后，圆形主动作在原位从 PTT 切换为发送；不额外挤出按钮。
            if (!primaryActionConfigured || !primaryActionIsSend) {
                voiceButton.setOnTouchListener(null);
                voiceButton.setOnClickListener(ignored -> submitInput());
                primaryActionConfigured = true;
                primaryActionIsSend = true;
            }
            voiceButton.setImageResource(R.drawable.ic_send_arrow);
            voiceButton.setSelected(false);
            voiceButton.setContentDescription(getString(state != null && state.hasRunningTask
                    ? R.string.conversation_append : R.string.conversation_send));
        } else {
            // 不要在 ACTION_DOWN 触发 publish() 后的重渲染中替换 listener：Android 会把同一
            // 手势的 ACTION_UP 交给当前 listener，替换会令快速点按留下未结束的 Host 会话。
            if (!primaryActionConfigured || primaryActionIsSend) {
                voiceButton.setOnClickListener(null);
                voiceButton.setOnTouchListener((ignored, event) -> onPttTouch(event));
                primaryActionConfigured = true;
                primaryActionIsSend = false;
            }
            voiceButton.setImageResource(R.drawable.ic_mic);
            renderPttVisual();
        }
    }

    private boolean hasSubmittableInput() {
        ConversationViewModel.State state = viewModel.state().getValue();
        if (!input.getText().toString().strip().isEmpty()) return true;
        return state != null && state.draftAttachments.stream()
                .anyMatch(com.matrix.agent.api.conversation.ConversationAttachment::isReady);
    }

    /** 全屏编辑器仍须明确展示随本轮冻结的受控上下文，避免“看不见的附件”式提交。 */
    private String fullscreenAttachmentSummary() {
        ConversationViewModel.State state = viewModel.state().getValue();
        if (state == null || state.draftAttachments.isEmpty()) return "";
        StringBuilder summary = new StringBuilder(
                getString(R.string.conversation_fullscreen_attachment_context));
        for (com.matrix.agent.api.conversation.ConversationAttachment attachment
                : state.draftAttachments) {
            String status = attachment.isReady()
                    ? getString(R.string.conversation_fullscreen_attachment_ready)
                    : attachment.isStaging()
                            ? getString(R.string.conversation_fullscreen_attachment_staging)
                            : getString(R.string.conversation_fullscreen_attachment_failed);
            summary.append('\n').append("• ").append(attachment.safeDisplayName)
                    .append(" · ").append(status);
        }
        return summary.toString();
    }

    private String stageText(@NonNull ConversationRuntimeStage stage) {
        if (stage.stage == ConversationRuntimeStage.STAGE_QUEUED) {
            return getString(R.string.conversation_stage_queued);
        }
        if (stage.stage == ConversationRuntimeStage.STAGE_PLANNING) {
            return getString(R.string.conversation_stage_planning);
        }
        if (stage.stage == ConversationRuntimeStage.STAGE_EXECUTING) {
            return stage.safeLabel == null || stage.safeLabel.isEmpty()
                    ? getString(R.string.conversation_stage_planning)
                    : getString(R.string.conversation_stage_executing, stage.safeLabel);
        }
        return getString(R.string.conversation_stage_planning);
    }

    /** 阶段条点击 → 定位该任务的用户消息（§6.3；不跳调试面板）。 */
    private void scrollToStageTask() {
        ConversationViewModel.State state = viewModel.state().getValue();
        if (state == null || state.runtimeStage == null) return;
        String taskId = state.runtimeStage.conversationTaskId;
        long targetSequence = Long.MIN_VALUE;
        for (ConversationViewModel.UiMessage message : state.messages) {
            if (message.role() == ConversationMessage.ROLE_USER
                    && taskId != null && taskId.equals(message.conversationTaskId())) {
                targetSequence = message.sequence();
                break;
            }
        }
        if (targetSequence == Long.MIN_VALUE
                || !(messageRows.getParent() instanceof android.widget.ScrollView scroll)) {
            return;
        }
        for (int i = 0; i < messageRows.getChildCount(); i++) {
            View child = messageRows.getChildAt(i);
            Object tag = child.getTag(R.id.conversation_row_sequence);
            if (tag instanceof Long sequence && sequence == targetSequence) {
                scroll.smoothScrollTo(0, Math.max(0, child.getTop() - dp(48)));
                return;
            }
        }
    }

    private void renderNotice(ConversationViewModel.State value) {
        CharSequence hint = null;
        if (value.transientError != null) {
            hint = value.transientError;
        } else if (value.conversationId == null) {
            hint = getText(R.string.conversation_bootstrapping);
        } else if (value.messages.isEmpty()) {
            hint = getText(R.string.conversation_empty);
        } else if (value.sending) {
            hint = getText(R.string.conversation_sending);
        } else if (value.hasMoreHistory) {
            hint = getText(R.string.conversation_history_more);
        }
        if (hint == null) {
            notice.setVisibility(View.GONE);
        } else {
            notice.setText(hint);
            notice.setVisibility(View.VISIBLE);
        }
    }

    private void renderMessages(List<ConversationViewModel.UiMessage> messages) {
        if (messages.size() == renderedCount && rowsMatch(messages)) {
            return; // 快照等值：避免订阅风暴期间的整列表重建
        }
        List<ConversationViewModel.UiMessage> previous = renderedSnapshot;
        renderedSnapshot = List.copyOf(messages);
        renderedCount = messages.size();
        android.widget.ScrollView scroll =
                messageRows.getParent() instanceof android.widget.ScrollView view ? view : null;
        // 整列表重建会丢掉滚动位置：先按"首条可见消息行"捕获锚点，再按列表变化
        // 类别决定贴底还是原位恢复——翻旧历史不能把用户拽回底部。
        ScrollAnchor anchor = captureAnchor(scroll);
        boolean stickToBottom = shouldStickToBottom(scroll, previous, messages);
        messageRows.removeAllViews();
        int from = Math.max(0, messages.size() - MAX_RENDERED_ROWS);
        for (int i = from; i < messages.size(); i++) {
            ConversationViewModel.UiMessage message = messages.get(i);
            messageRows.addView(buildRow(message));
            // 过程不是最终答复的附属文案。它跟随本轮用户输入出现，最终回复自然排在其后；
            // 因此调试事件到达时无需等待 assistant message 落库。
            if (hasDebugTracePanel(message)) {
                messageRows.addView(buildDebugTraceRow(message));
            }
        }
        if (scroll == null) return;
        if (stickToBottom) {
            scroll.post(() -> scroll.fullScroll(android.widget.ScrollView.FOCUS_DOWN));
        } else {
            final ScrollAnchor captured = anchor;
            scroll.post(() -> restoreAnchor(scroll, captured));
        }
    }

    /** 列表变化类别：决定整列表重建后的滚动策略。 */
    private enum ListGrowth { INITIAL, PREPENDED, APPENDED, REPLACED, IN_PLACE }

    private boolean shouldStickToBottom(android.widget.ScrollView scroll,
            List<ConversationViewModel.UiMessage> previous,
            List<ConversationViewModel.UiMessage> next) {
        switch (classifyGrowth(previous, next)) {
            case INITIAL:
            case REPLACED:
                // 首次载入或切换会话后的全量重载：贴底展示最新一轮。
                return true;
            case APPENDED:
                // 新消息到达：只有用户本来就在底部才跟随，读旧历史时不打断。
                return isNearBottom(scroll);
            case PREPENDED:
            case IN_PLACE:
            default:
                // 向前翻页或原地状态更新：保持锚点原位。
                return false;
        }
    }

    private static ListGrowth classifyGrowth(List<ConversationViewModel.UiMessage> previous,
            List<ConversationViewModel.UiMessage> next) {
        if (previous.isEmpty()) return ListGrowth.INITIAL;
        if (next.size() == previous.size()) return ListGrowth.IN_PLACE;
        if (isHead(previous, next, 0)) return ListGrowth.APPENDED;
        if (isHead(previous, next, next.size() - previous.size())) return ListGrowth.PREPENDED;
        return ListGrowth.REPLACED;
    }

    /** previous 是否与 next 在 offset 处起的连续子序列按 sequence 完全一致。 */
    private static boolean isHead(List<ConversationViewModel.UiMessage> previous,
            List<ConversationViewModel.UiMessage> next, int offset) {
        if (offset < 0 || next.size() - offset < previous.size()) return false;
        for (int i = 0; i < previous.size(); i++) {
            if (previous.get(i).sequence() != next.get(offset + i).sequence()) return false;
        }
        return true;
    }

    private boolean isNearBottom(android.widget.ScrollView scroll) {
        if (scroll == null) return true;
        View content = scroll.getChildAt(0);
        return content == null
                || content.getHeight() - scroll.getHeight() - scroll.getScrollY() <= activity().dp(36);
    }

    /** 重建前的滚动锚点：第一条与视口顶相交的消息行、其行内偏移与绝对 scrollY。 */
    private ScrollAnchor captureAnchor(android.widget.ScrollView scroll) {
        if (scroll == null) return null;
        int scrollY = scroll.getScrollY();
        for (int i = 0; i < messageRows.getChildCount(); i++) {
            View child = messageRows.getChildAt(i);
            Object sequence = child.getTag(R.id.conversation_row_sequence);
            if (!(sequence instanceof Long value) || child.getBottom() <= scrollY) continue;
            return new ScrollAnchor(value, scrollY - child.getTop(), scrollY);
        }
        // 视口内只有过程卡可锚：按绝对 scrollY 恢复，由内容高度收敛。
        return new ScrollAnchor(Long.MIN_VALUE, 0, scrollY);
    }

    private void restoreAnchor(android.widget.ScrollView scroll, ScrollAnchor anchor) {
        if (anchor == null) {
            scroll.fullScroll(android.widget.ScrollView.FOCUS_DOWN);
            return;
        }
        if (anchor.sequence != Long.MIN_VALUE) {
            for (int i = 0; i < messageRows.getChildCount(); i++) {
                View child = messageRows.getChildAt(i);
                Object sequence = child.getTag(R.id.conversation_row_sequence);
                if (sequence instanceof Long value && value == anchor.sequence) {
                    scroll.scrollTo(0, child.getTop() + anchor.offsetWithinRow);
                    return;
                }
            }
        }
        View content = scroll.getChildAt(0);
        int maxScroll = content == null ? 0
                : Math.max(0, content.getHeight() - scroll.getHeight());
        scroll.scrollTo(0, Math.min(anchor.absoluteScrollY, maxScroll));
    }

    private static final class ScrollAnchor {
        final long sequence;
        final int offsetWithinRow;
        final int absoluteScrollY;

        ScrollAnchor(long sequence, int offsetWithinRow, int absoluteScrollY) {
            this.sequence = sequence;
            this.offsetWithinRow = offsetWithinRow;
            this.absoluteScrollY = absoluteScrollY;
        }
    }

    private boolean rowsMatch(List<ConversationViewModel.UiMessage> messages) {
        int expectedRowCount = 0;
        for (ConversationViewModel.UiMessage message : messages) {
            expectedRowCount += hasDebugTracePanel(message) ? 2 : 1;
        }
        if (messageRows.getChildCount() != expectedRowCount) return false;
        int rowIndex = 0;
        for (ConversationViewModel.UiMessage expected : messages) {
            String signature = signatureOf(expected);
            Object tag = messageRows.getChildAt(rowIndex++).getTag(R.id.conversation_messages);
            if (!(tag instanceof String value) || !signature.equals(value)) {
                return false;
            }
            if (hasDebugTracePanel(expected)) {
                Object processTag = messageRows.getChildAt(rowIndex++)
                        .getTag(R.id.conversation_messages);
                if (!(processTag instanceof String processValue)
                        || !("process:" + signature).equals(processValue)) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean hasDebugTracePanel(ConversationViewModel.UiMessage message) {
        return message.role() == ConversationMessage.ROLE_USER
                && BuildConfig.MATRIX_DEBUG_TRACE_UI
                && message.debugTraces() != null && !message.debugTraces().isEmpty();
    }

    private View buildRow(ConversationViewModel.UiMessage message) {
        int width = messageRows.getWidth() > 0 ? messageRows.getWidth()
                : getResources().getDisplayMetrics().widthPixels;
        View row = new ConversationMessageRenderer(requireContext()).create(message,
                width - messageRows.getPaddingStart() - messageRows.getPaddingEnd(), this::showActions);
        row.setTag(R.id.conversation_messages, signatureOf(message));
        return row;
    }

    /**
     * 独立的过程卡，不属于最终 assistant 气泡。它的顺序由 renderMessages 保证为：
     * USER → PROCESS → ASSISTANT；真实事实的所有权仍在 user/task link，不被 UI 重写。
     */
    private View buildDebugTraceRow(ConversationViewModel.UiMessage userMessage) {
        int width = messageRows.getWidth() > 0 ? messageRows.getWidth()
                : getResources().getDisplayMetrics().widthPixels;
        View row = new ConversationTraceRenderer(requireContext()).create(userMessage,
                width - messageRows.getPaddingStart() - messageRows.getPaddingEnd());
        row.setTag(R.id.conversation_messages, "process:" + signatureOf(userMessage));
        return row;
    }

    private int color(int resource) {
        return ContextCompat.getColor(requireContext(), resource);
    }

    private void showActions(ConversationViewModel.UiMessage message) {
        boolean isUser = message.role() == ConversationMessage.ROLE_USER;
        boolean assistantCompleted = message.role() == ConversationMessage.ROLE_ASSISTANT
                && message.status() == ConversationMessage.STATUS_COMPLETED;
        boolean userCompleted = isUser && isTerminalStatus(message.status());
java.util.List<String> options = new java.util.ArrayList<>();
        options.add(getString(R.string.conversation_menu_quote));
        options.add(getString(R.string.conversation_menu_copy));
        if (userCompleted) options.add(getString(R.string.conversation_menu_fork));
        if (assistantCompleted) {
            options.add(getString(R.string.conversation_menu_read_aloud));
        }
        String[] items = options.toArray(new String[0]);
        if (items.length == 0) return;
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.conversation_menu_actions)
                .setItems(items, (dialog, which) -> {
                    String chosen = items[which];
                    if (getString(R.string.conversation_menu_quote).equals(chosen)) {
                        quoteReply(message);
                    } else if (getString(R.string.conversation_menu_copy).equals(chosen)) {
                        copyText(message.text());
                    } else if (getString(R.string.conversation_menu_fork).equals(chosen)) {
                        viewModel.forkFromHere(message.sequence(), childId ->
                                android.widget.Toast.makeText(requireContext(),
                                        R.string.conversation_fork_created,
                                        android.widget.Toast.LENGTH_SHORT).show());
                    } else if (getString(R.string.conversation_menu_read_aloud)
                            .equals(chosen)) {
                        viewModel.readAloud(message.messageId());
                    }
                })
                .show();
    }

    private void quoteReply(ConversationViewModel.UiMessage message) {
        // 已挂在底栏的 input 不能再作为 AlertDialog 的 child；复用它会触发
        // “specified child already has a parent” 并让长按引用直接崩溃。
        android.widget.EditText quoteInput = new android.widget.EditText(requireContext());
        quoteInput.setHint(R.string.conversation_input_hint);
        quoteInput.setMinLines(3);
        quoteInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.conversation_quote_prompt)
                .setView(quoteInput)
                .setPositiveButton(R.string.conversation_send, (dialog, which) -> {
                    String text = quoteInput.getText().toString();
                    if (!text.isBlank()) {
                        viewModel.sendQuoting(message.messageId(), text);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void copyText(String text) {
        android.content.ClipboardManager clipboard =
                (android.content.ClipboardManager) requireContext()
                        .getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                    "conversation", text));
        }
    }

    private void promptRename() {
        android.widget.EditText edit = new android.widget.EditText(requireContext());
        ConversationViewModel.State current = viewModel.state().getValue();
        if (current != null && current.conversationTitle != null) {
            edit.setText(current.conversationTitle);
        }
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.conversation_menu_rename)
                .setView(edit)
                .setPositiveButton(R.string.conversation_send, (dialog, which) -> {
                    String title = edit.getText().toString();
                    if (!title.isBlank()) {
                        viewModel.rename(title);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static String signatureOf(ConversationViewModel.UiMessage message) {
        StringBuilder traceSignature = new StringBuilder();
        if (message.debugTraces() != null) {
            for (DebugTraceWireEvent event : message.debugTraces()) {
                traceSignature.append(event.traceId).append(':').append(event.partIndex)
                        .append(':').append(event.payload == null ? 0 : event.payload.hashCode())
                        .append(';');
            }
        }
        return message.sequence() + ":" + message.status() + ":" + message.text().length()
                + ":" + message.executionTraces().hashCode() + ":" + traceSignature;
    }

    private boolean isTerminalStatus(int status) {
        return status != ConversationMessage.STATUS_ACCEPTED
                && status != ConversationMessage.STATUS_RUNNING;
    }

    private LauncherActivity activity() {
        return (LauncherActivity) requireActivity();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + .5f);
    }
}
