package com.matrix.agent.launcher.presentation;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.graphics.drawable.GradientDrawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.matrix.agent.api.conversation.ConversationMessage;
import com.matrix.agent.launcher.LauncherActivity;
import com.matrix.agent.launcher.R;

import java.util.List;

/**
 * 对话页（阶段 A：文字通道）。纯渲染：全部状态来自 ViewModel 的不可变投影；
 * EXECUTION_UNKNOWN 按契约渲染为“执行结果未知”提示行，绝不显示成取消或失败。
 */
public final class ConversationFragment extends Fragment {

    private static final int MAX_RENDERED_ROWS = 200;

    private LinearLayout messageRows;
    private TextView notice;
    private EditText input;
    private View sendButton;
    private View cancelButton;
    private android.widget.Button voiceButton;
    private TextView dynamicTitle;
    private TextView summaryBadge;
    private View exportButton;
    private ConversationViewModel viewModel;
    private int renderedCount;

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater,
            @Nullable ViewGroup parent, @Nullable Bundle state) {
        LauncherActivity activity = activity();
        View root = inflater.inflate(R.layout.fragment_conversation, parent, false);
        viewModel = new ViewModelProvider(requireActivity(), activity.viewModelFactory())
                .get(ConversationViewModel.class);
        messageRows = root.findViewById(R.id.conversation_messages);
        notice = root.findViewById(R.id.conversation_notice);
        input = root.findViewById(R.id.conversation_input);
        sendButton = root.findViewById(R.id.conversation_send);
        cancelButton = root.findViewById(R.id.conversation_cancel);
        voiceButton = root.findViewById(R.id.conversation_voice);
        dynamicTitle = root.findViewById(R.id.conversation_dynamic_title);
        summaryBadge = root.findViewById(R.id.conversation_summary_badge);
        exportButton = root.findViewById(R.id.conversation_export);
        exportButton.setOnClickListener(ignored -> exportCurrentConversation());
        dynamicTitle.setOnLongClickListener(ignored -> {
            promptRename();
            return true;
        });

        sendButton.setOnClickListener(ignored -> submitInput());
        input.setOnEditorActionListener((view, actionId, event) -> {
            if (event != null && event.getAction() == KeyEvent.ACTION_DOWN) {
                submitInput();
                return true;
            }
            return false;
        });
        cancelButton.setOnClickListener(ignored -> viewModel.cancelLatest());
        voiceButton.setOnTouchListener((ignored, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                viewModel.startRecording();
                return true;
            }
            if (event.getAction() == android.view.MotionEvent.ACTION_UP
                    || event.getAction() == android.view.MotionEvent.ACTION_CANCEL) {
                viewModel.stopRecording();
                return true;
            }
            return true;
        });
        root.findViewById(R.id.conversation_scroll).setOnClickListener(
                ignored -> viewModel.refresh());

        viewModel.state().observe(getViewLifecycleOwner(), this::render);
        new ViewModelProvider(requireActivity(), activity.viewModelFactory())
                .get(LauncherViewModel.class)
                .connectionState().observe(getViewLifecycleOwner(), value -> {
                    if (viewModel.isHostConnected()) {
                        viewModel.start();
                    }
                });
        return root;
    }

    @Override public void onStart() {
        super.onStart();
        if (viewModel.isHostConnected()) {
            viewModel.start();
        }
    }

    @Override public void onStop() {
        viewModel.closeSubscription();
        super.onStop();
    }

    private void submitInput() {
        String text = input.getText().toString();
        if (text.isBlank()) return;
        input.setText("");
        // 始终作为新消息发送——keyed lane 自动排队同会话任务，不会并发。
        // 不在有运行任务时静默转 steer（用户不知道消息去了哪）。
        viewModel.send(text);
    }

    private boolean currentStateHasRunning() {
        ConversationViewModel.State state = viewModel.state().getValue();
        return state != null && state.hasRunningTask;
    }

    private void render(@NonNull ConversationViewModel.State value) {
        renderNotice(value);
        renderMessages(value.messages);
        cancelButton.setVisibility(value.hasRunningTask ? View.VISIBLE : View.GONE);
        sendButton.setEnabled(!value.sending);
        voiceButton.setEnabled(value.conversationId != null);
        voiceButton.setText(value.recording
                ? R.string.conversation_voice_recording : R.string.conversation_voice_hold);
        if (value.conversationTitle != null) {
            dynamicTitle.setText(value.conversationTitle);
        }
        summaryBadge.setVisibility(value.summaryActive ? View.VISIBLE : View.GONE);
        exportButton.setEnabled(value.conversationId != null
                && !value.messages.isEmpty());
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
        renderedCount = messages.size();
        messageRows.removeAllViews();
        int from = Math.max(0, messages.size() - MAX_RENDERED_ROWS);
        for (int i = from; i < messages.size(); i++) {
            messageRows.addView(buildRow(messages.get(i)));
        }
        if (messageRows.getParent() instanceof android.widget.ScrollView scroll) {
            scroll.post(() -> scroll.fullScroll(android.widget.ScrollView.FOCUS_DOWN));
        }
    }

    private boolean rowsMatch(List<ConversationViewModel.UiMessage> messages) {
        if (messageRows.getChildCount() != messages.size()) return false;
        for (int i = 0; i < messages.size(); i++) {
            Object tag = messageRows.getChildAt(i).getTag(R.id.conversation_messages);
            ConversationViewModel.UiMessage expected = messages.get(i);
            if (!(tag instanceof String signature) || !signature.equals(signatureOf(expected))) {
                return false;
            }
        }
        return true;
    }

    private View buildRow(ConversationViewModel.UiMessage message) {
        float density = getResources().getDisplayMetrics().density;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int maxBubbleWidth = (int) (screenWidth * 0.78f);
        boolean isUser = message.role() == ConversationMessage.ROLE_USER;
        boolean isSystem = message.role() == ConversationMessage.ROLE_SYSTEM;

        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.VERTICAL);
        int padH = (int) (13 * density + .5f);
        int padV = (int) (9 * density + .5f);
        row.setPadding(padH, padV, padH, padV);
        row.setTag(R.id.conversation_messages, signatureOf(message));

        // 气泡背景：user 浅绿，assistant 白色卡片，system 灰色
        android.graphics.drawable.GradientDrawable bubble =
                new android.graphics.drawable.GradientDrawable();
        bubble.setCornerRadius(12 * density);
        if (isUser) {
            bubble.setColor(0xFFD4EDDA);
            bubble.setStroke(1, 0xFFB8DCC5);
        } else if (isSystem) {
            bubble.setColor(0xFFF0F2F1);
        } else {
            bubble.setColor(0xFFFEFFFC);
            bubble.setStroke(1, 0xFFD5E0DC);
        }
        row.setBackground(bubble);

        // 左右对齐：助手靠左、用户靠右（聊天式布局）
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = isUser
                ? android.view.Gravity.END
                : android.view.Gravity.START;
        int edgeMargin = (int) (4 * density + .5f);
        int gapMargin = (int) (48 * density + .5f);
        if (isUser) {
            params.leftMargin = gapMargin;
            params.rightMargin = edgeMargin;
        } else {
            params.leftMargin = edgeMargin;
            params.rightMargin = gapMargin;
        }
        params.bottomMargin = (int) (5 * density + .5f);
        row.setLayoutParams(params);

        // 标题行（角色名 + 来源标签）
        TextView heading = new TextView(requireContext());
        heading.setTextSize(10);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        heading.setTextColor(ContextCompat.getColor(requireContext(),
                isUser ? R.color.matrix_accent : R.color.matrix_primary));
        heading.setText(headingText(message));
        heading.setMaxWidth(maxBubbleWidth);
        row.addView(heading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // 正文
        TextView body = new TextView(requireContext());
        body.setTextSize(14);
        body.setTextColor(ContextCompat.getColor(requireContext(),
                isSystem ? R.color.matrix_muted : R.color.matrix_text));
        body.setText(message.text());
        body.setMaxWidth(maxBubbleWidth);
        row.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // 状态行（仅用户消息非终态时显示）
        if (isUser && !isTerminalStatus(message.status())) {
            TextView status = new TextView(requireContext());
            status.setTextSize(10);
            status.setTextColor(ContextCompat.getColor(requireContext(),
                    R.color.matrix_muted));
            status.setText(statusText(message.status()));
            status.setMaxWidth(maxBubbleWidth);
            row.addView(status, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        // steer 附属输入注记（评估 v1.0 §4.3）：“已并入”只由 OFFERED 声称
        if (isUser && message.inputKind() == ConversationMessage.INPUT_STEER) {
            TextView steerNote = new TextView(requireContext());
            steerNote.setTextSize(10);
            steerNote.setTypeface(Typeface.DEFAULT_BOLD);
            steerNote.setTextColor(ContextCompat.getColor(requireContext(),
                    R.color.matrix_primary));
            steerNote.setText(steerNoteText(message.steerDeliveryState(),
                            message.status()));
            steerNote.setMaxWidth(maxBubbleWidth);
            row.addView(steerNote, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        // 能力事实轨迹（评估 v1.0 §4.3）：两段式“请求 X → 核验为 Y”，不一致以 Y 为准
        if (isUser && message.executionTraces() != null
                && !message.executionTraces().isEmpty()) {
            for (com.matrix.agent.api.conversation.CapabilityTraceEntry trace
                    : message.executionTraces()) {
                row.addView(buildTraceRow(trace, maxBubbleWidth, density));
            }
        }

        // 长按菜单（阶段 3/4 入口）：收藏 / 引用回复 / 分支 / 朗读 / 复制
        row.setOnLongClickListener(view -> {
            showActions(message);
            return true;
        });

        return row;
    }

    private View buildTraceRow(com.matrix.agent.api.conversation.CapabilityTraceEntry trace,
            int maxBubbleWidth, float density) {
        LinearLayout line = new LinearLayout(requireContext());
        line.setOrientation(LinearLayout.HORIZONTAL);
        TextView text = new TextView(requireContext());
        text.setTextSize(10);
        text.setTextColor(ContextCompat.getColor(requireContext(), R.color.matrix_muted));
        StringBuilder sb = new StringBuilder("· ");
        sb.append(trace.friendlyName == null ? trace.capabilityId : trace.friendlyName);
        if (trace.requestedDisplay != null) {
            sb.append("：请求 ").append(trace.requestedDisplay);
        }
        if (trace.verifiedDisplay != null) {
            sb.append(" → 核验为 ").append(trace.verifiedDisplay);
        }
        sb.append("（").append(verifyText(trace.verificationState)).append("）");
        text.setText(sb.toString());
        text.setMaxWidth(maxBubbleWidth);
        line.addView(text, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return line;
    }

    private String verifyText(String verificationState) {
        String state = verificationState == null ? "" : verificationState;
        if (com.matrix.agent.api.conversation.CapabilityTraceEntry.VERIFY_MISMATCH
                .equals(state)) {
            return getString(R.string.conversation_trace_mismatch);
        }
        if (com.matrix.agent.api.conversation.CapabilityTraceEntry.VERIFY_UNKNOWN
                .equals(state)) {
            return getString(R.string.conversation_trace_unknown);
        }
        if (com.matrix.agent.api.conversation.CapabilityTraceEntry.VERIFY_UNAVAILABLE
                .equals(state)) {
            return getString(R.string.conversation_trace_unavailable);
        }
        return getString(R.string.conversation_trace_verified);
    }

    private String steerNoteText(int steerDeliveryState, int status) {
        if (status == ConversationMessage.STATUS_FAILED) {
            return getString(R.string.conversation_steer_failed);
        }
        switch (steerDeliveryState) {
            case ConversationMessage.STEER_DELIVERY_OFFERED:
                return getString(R.string.conversation_steer_offered);
            case ConversationMessage.STEER_DELIVERY_PENDING:
                // 恢复后仍 PENDING：诚实显示“未确认”，宿主终态不谎称并入
                return isTerminalStatus(status)
                        ? getString(R.string.conversation_steer_recovered)
                        : getString(R.string.conversation_steer_pending);
            default:
                return getString(R.string.conversation_steer_failed);
        }
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
        input.setText("");
        new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                .setTitle(R.string.conversation_quote_prompt)
                .setView(input)
                .setPositiveButton(R.string.conversation_send, (dialog, which) -> {
                    String text = input.getText().toString();
                    input.setText("");
                    if (!text.isBlank()) {
                        viewModel.sendQuoting(message.messageId(), text);
                    }
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) ->
                        input.setText(""))
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

    private void exportCurrentConversation() {
        viewModel.exportCurrent(uri -> {
            android.content.Intent share = new android.content.Intent(
                    android.content.Intent.ACTION_SEND);
            share.setType("text/markdown");
            share.putExtra(android.content.Intent.EXTRA_STREAM,
                    android.net.Uri.parse(uri));
            share.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(android.content.Intent.createChooser(share,
                    getString(R.string.conversation_export)));
        });
    }

    private static String signatureOf(ConversationViewModel.UiMessage message) {
        return message.sequence() + ":" + message.status() + ":" + message.text().length();
    }

    private String headingText(ConversationViewModel.UiMessage message) {
        return switch (message.role()) {
            case ConversationMessage.ROLE_USER -> getString(R.string.conversation_role_user)
                    + channelSuffix(message.channel());
            case ConversationMessage.ROLE_ASSISTANT -> getString(
                    R.string.conversation_role_assistant);
            default -> getString(R.string.conversation_role_system);
        };
    }

    private String channelSuffix(int channel) {
        return switch (channel) {
            case ConversationMessage.CHANNEL_PTT -> " · " + getString(
                    R.string.conversation_channel_voice);
            case ConversationMessage.CHANNEL_WAKE -> " · " + getString(
                    R.string.conversation_channel_wake);
            default -> "";
        };
    }

    private boolean isTerminalStatus(int status) {
        return status != ConversationMessage.STATUS_ACCEPTED
                && status != ConversationMessage.STATUS_RUNNING;
    }

    private String statusText(int status) {
        if (status == ConversationMessage.STATUS_ACCEPTED) {
            return getString(R.string.conversation_status_accepted);
        }
        if (status == ConversationMessage.STATUS_RUNNING) {
            return getString(R.string.conversation_status_running);
        }
        return "";
    }

    private LauncherActivity activity() {
        return (LauncherActivity) requireActivity();
    }
}
