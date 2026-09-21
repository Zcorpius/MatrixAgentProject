package com.matrix.agent.launcher.presentation;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
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
import com.matrix.agent.api.debug.DebugTraceWireEvent;
import com.matrix.agent.launcher.BuildConfig;
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
    private ConversationViewModel viewModel;
    private int renderedCount;
    /** 上一次重建入列的消息快照：用于把本次变化归类为前插/追加/重载，驱动滚动策略。 */
    private List<ConversationViewModel.UiMessage> renderedSnapshot = List.of();

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
        dynamicTitle.setOnLongClickListener(ignored -> {
            promptRename();
            return true;
        });

        sendButton.setOnClickListener(ignored -> submitInput());
        input.setOnEditorActionListener((view, actionId, event) -> {
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
        float density = getResources().getDisplayMetrics().density;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        boolean isUser = message.role() == ConversationMessage.ROLE_USER;
        boolean isSystem = message.role() == ConversationMessage.ROLE_SYSTEM;
        int avatarSize = (int) (42 * density + .5f);
        int avatarGap = (int) (8 * density + .5f);
        // 头像距屏幕的边距唯一由消息列表容器的 padding（XML）决定：行自身不再叠加水平
        // padding，头像贴近屏幕边缘（微信式），气泡随之外移并获得更多可用宽度。
        int contentInset = Math.max(messageRows.getPaddingStart(),
                messageRows.getPaddingEnd());
        // 头像与气泡必须共同受屏宽约束，不能让长文本把头像挤出可视区。
        int maxBubbleWidth = Math.min((int) (screenWidth * 0.72f),
                screenWidth - avatarSize - avatarGap - contentInset * 2);

        // 外层负责微信式左右编排；气泡只承载内容，不再承载“用户/助手”身份文字。
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.TOP);
        row.setPadding(0, (int) (7 * density + .5f), 0,
                (int) (7 * density + .5f));
        row.setTag(R.id.conversation_messages, signatureOf(message));
        row.setTag(R.id.conversation_row_sequence, message.sequence());
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = (int) (3 * density + .5f);
        row.setLayoutParams(rowParams);

        LinearLayout bubbleContent = new LinearLayout(requireContext());
        bubbleContent.setOrientation(LinearLayout.VERTICAL);
        int bubblePadH = (int) (12 * density + .5f);
        int bubblePadV = (int) (9 * density + .5f);
        bubbleContent.setPadding(bubblePadH, bubblePadV, bubblePadH, bubblePadV);

        android.graphics.drawable.GradientDrawable bubble =
                new android.graphics.drawable.GradientDrawable();
        bubble.setCornerRadius(15 * density);
        if (isUser) {
            bubble.setColor(color(R.color.matrix_chat_user_bubble));
            bubble.setStroke(1, color(R.color.matrix_chat_user_bubble_stroke));
        } else if (isSystem) {
            bubble.setColor(color(R.color.matrix_chat_system_bubble));
        } else {
            bubble.setColor(color(R.color.matrix_chat_assistant_bubble));
            bubble.setStroke(1, color(R.color.matrix_chat_assistant_bubble_stroke));
        }
        bubbleContent.setBackground(bubble);
        LinearLayout.LayoutParams bubbleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        TextView body = new TextView(requireContext());
        body.setTextSize(14);
        body.setTextColor(ContextCompat.getColor(requireContext(),
                isSystem ? R.color.matrix_muted : R.color.matrix_text));
        body.setText(message.text());
        body.setMaxWidth(maxBubbleWidth);
        bubbleContent.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (isUser && !isTerminalStatus(message.status())) {
            TextView status = new TextView(requireContext());
            status.setTextSize(10);
            status.setTextColor(ContextCompat.getColor(requireContext(), R.color.matrix_muted));
            status.setText(statusText(message.status()));
            status.setMaxWidth(maxBubbleWidth);
            bubbleContent.addView(status, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        if (isUser && message.inputKind() == ConversationMessage.INPUT_STEER) {
            TextView steerNote = new TextView(requireContext());
            steerNote.setTextSize(10);
            steerNote.setTypeface(Typeface.DEFAULT_BOLD);
            steerNote.setTextColor(ContextCompat.getColor(requireContext(), R.color.matrix_primary));
            steerNote.setText(steerNoteText(message.steerDeliveryState(), message.status()));
            steerNote.setMaxWidth(maxBubbleWidth);
            bubbleContent.addView(steerNote, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        if (isSystem) {
            row.addView(weightSpacer());
            row.addView(bubbleContent, bubbleParams);
            row.addView(weightSpacer());
        } else if (isUser) {
            row.addView(weightSpacer());
            row.addView(bubbleContent, bubbleParams);
            row.addView(avatarView(R.drawable.avatar_user_penguin,
                    R.string.conversation_avatar_user, R.color.matrix_chat_avatar_user_bg),
                    avatarLayoutParams(avatarSize, avatarGap, true));
        } else {
            row.addView(avatarView(R.drawable.avatar_assistant_matrix,
                    R.string.conversation_avatar_assistant, R.color.matrix_chat_avatar_assistant_bg),
                    avatarLayoutParams(avatarSize, avatarGap, false));
            row.addView(bubbleContent, bubbleParams);
            row.addView(weightSpacer());
        }

        row.setOnLongClickListener(view -> {
            showActions(message);
            return true;
        });
        return row;
    }

    private View weightSpacer() {
        View spacer = new View(requireContext());
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        return spacer;
    }

    private LinearLayout.LayoutParams avatarLayoutParams(int size, int gap, boolean userSide) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
        if (userSide) params.setMarginStart(gap);
        else params.setMarginEnd(gap);
        return params;
    }

    private android.widget.ImageView avatarView(int drawableRes, int descriptionRes,
            int backgroundColorRes) {
        android.widget.ImageView avatar = new android.widget.ImageView(requireContext());
        avatar.setImageResource(drawableRes);
        avatar.setContentDescription(getString(descriptionRes));
        avatar.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(color(backgroundColorRes));
        avatar.setBackground(mask);
        avatar.setClipToOutline(true);
        return avatar;
    }

    /**
     * 独立的过程卡，不属于最终 assistant 气泡。它的顺序由 renderMessages 保证为：
     * USER → PROCESS → ASSISTANT；真实事实的所有权仍在 user/task link，不被 UI 重写。
     */
    private View buildDebugTraceRow(ConversationViewModel.UiMessage userMessage) {
        float density = getResources().getDisplayMetrics().density;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int maxWidth = (int) (screenWidth * 0.78f);

        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding((int) (13 * density + .5f), (int) (8 * density + .5f),
                (int) (13 * density + .5f), (int) (8 * density + .5f));
        row.setBackground(roundedBackground(color(R.color.matrix_trace_group_bg),
                color(R.color.matrix_trace_group_stroke), 12 * density));
        row.setTag(R.id.conversation_messages, "process:" + signatureOf(userMessage));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(maxWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = android.view.Gravity.START;
        params.leftMargin = (int) (4 * density + .5f);
        params.rightMargin = (int) (48 * density + .5f);
        params.bottomMargin = (int) (5 * density + .5f);
        row.setLayoutParams(params);
        row.addView(buildDebugTracePanel(userMessage.debugTraces(), maxWidth - (int) (26 * density),
                density));
        return row;
    }

    /**
     * Operit 同构的过程组：一轮 AI 回复把连续的思考、工具请求和工具结果编排成一个
     * 可折叠组；组内“思考”和每个 capability 又各自独立折叠。这里不是 Logcat 的镜像：
     * 只展示写时已脱敏的事实投影，且只在 debug/internal 构建可达。
     */
    private View buildDebugTracePanel(List<DebugTraceWireEvent> rawEvents, int maxBubbleWidth,
            float density) {
        List<DebugTraceTimeline.Node> nodes = DebugTraceTimeline.from(rawEvents);
        int thinkingCount = 0;
        int toolCount = 0;
        for (DebugTraceTimeline.Node node : nodes) {
            if (node.kind == DebugTraceTimeline.Kind.THINKING) thinkingCount++;
            if (node.kind == DebugTraceTimeline.Kind.TOOL) toolCount++;
        }

        LinearLayout panel = new LinearLayout(requireContext());
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(0, (int) (7 * density + .5f), 0, (int) (4 * density + .5f));

        TextView header = new TextView(requireContext());
        header.setTextSize(11);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setTextColor(color(R.color.matrix_trace_group_title));
        header.setCompoundDrawablePadding((int) (5 * density + .5f));
        header.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_menu_info_details,
                0, android.R.drawable.arrow_down_float, 0);
        String groupText = thinkingCount == 0
                ? getString(R.string.conversation_debug_trace_group_without_reasoning, toolCount)
                : getString(R.string.conversation_debug_trace_group, thinkingCount, toolCount);
        header.setText(groupText);
        header.setContentDescription(groupText);
        header.setPadding(0, (int) (3 * density + .5f), 0, (int) (4 * density + .5f));
        panel.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout detail = new LinearLayout(requireContext());
        detail.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable background = roundedBackground(color(R.color.matrix_trace_detail_bg),
                color(R.color.matrix_trace_detail_stroke), 9 * density);
        detail.setBackground(background);
        int inset = (int) (8 * density + .5f);
        detail.setPadding(inset, inset, inset, inset);
        // Operit 的静态消息默认收起；进行中的消息才能自动展开。Matrix 只持久化 final，
        // 因而重进会话保持稳定、可预期的收起状态。
        detail.setVisibility(View.GONE);
        for (int i = 0; i < nodes.size(); i++) {
            detail.addView(buildDebugTraceNode(nodes.get(i), i == nodes.size() - 1,
                    maxBubbleWidth - inset * 2, density));
        }
        panel.addView(detail, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        header.setOnClickListener(ignored -> {
            boolean expanding = detail.getVisibility() != View.VISIBLE;
            detail.setVisibility(expanding ? View.VISIBLE : View.GONE);
            header.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_menu_info_details,
                    0, expanding ? android.R.drawable.arrow_up_float
                            : android.R.drawable.arrow_down_float, 0);
        });
        return panel;
    }

    private View buildDebugTraceNode(DebugTraceTimeline.Node node, boolean last, int maxWidth,
            float density) {
        LinearLayout item = new LinearLayout(requireContext());
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setPadding(0, 0, 0, last ? 0 : (int) (7 * density + .5f));

        LinearLayout rail = new LinearLayout(requireContext());
        rail.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        rail.setOrientation(LinearLayout.VERTICAL);
        TextView dot = new TextView(requireContext());
        dot.setText(node.kind == DebugTraceTimeline.Kind.THINKING ? "✦" : "●");
        dot.setTextSize(12);
        dot.setGravity(android.view.Gravity.CENTER);
        dot.setTextColor(node.kind == DebugTraceTimeline.Kind.TOOL
                ? ContextCompat.getColor(requireContext(), node.statusColorRes())
                : color(R.color.matrix_trace_thinking_dot));
        rail.addView(dot, new LinearLayout.LayoutParams((int) (18 * density + .5f),
                (int) (18 * density + .5f)));
        if (!last) {
            View guide = new View(requireContext());
            guide.setBackgroundColor(color(R.color.matrix_trace_guide_line));
            LinearLayout.LayoutParams guideParams = new LinearLayout.LayoutParams(
                    Math.max(1, (int) density), 0, 1f);
            guideParams.gravity = android.view.Gravity.CENTER_HORIZONTAL;
            rail.addView(guide, guideParams);
        }
        item.addView(rail, new LinearLayout.LayoutParams((int) (18 * density + .5f),
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding((int) (6 * density + .5f), 0, 0, 0);
        TextView title = new TextView(requireContext());
        title.setTextSize(11);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(color(R.color.matrix_trace_node_title));
        title.setCompoundDrawablePadding((int) (4 * density + .5f));
        title.setCompoundDrawablesWithIntrinsicBounds(0, 0, android.R.drawable.arrow_down_float, 0);
        title.setText(node.title(requireContext()));
        title.setMaxWidth(maxWidth - (int) (24 * density + .5f));
        card.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView preview = new TextView(requireContext());
        preview.setTextSize(10);
        preview.setTextColor(color(R.color.matrix_trace_node_preview));
        preview.setText(node.preview(requireContext()));
        preview.setMaxLines(2);
        preview.setEllipsize(android.text.TextUtils.TruncateAt.END);
        preview.setMaxWidth(maxWidth - (int) (24 * density + .5f));
        card.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView content = new TextView(requireContext());
        content.setTextSize(10);
        content.setTypeface(Typeface.MONOSPACE);
        content.setTextColor(color(R.color.matrix_trace_node_content));
        content.setText(node.detail(requireContext()));
        content.setMaxWidth(maxWidth - (int) (30 * density + .5f));
        content.setPadding((int) (9 * density + .5f), (int) (5 * density + .5f), 0, 0);
        content.setBackground(roundedBackground(color(R.color.matrix_trace_node_content_bg),
                0x00000000, 5 * density));
        content.setVisibility(View.GONE);
        card.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        title.setOnClickListener(ignored -> {
            boolean expanding = content.getVisibility() != View.VISIBLE;
            content.setVisibility(expanding ? View.VISIBLE : View.GONE);
            title.setCompoundDrawablesWithIntrinsicBounds(0, 0,
                    expanding ? android.R.drawable.arrow_up_float
                            : android.R.drawable.arrow_down_float, 0);
        });
        item.addView(card, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return item;
    }

    private GradientDrawable roundedBackground(int color, int strokeColor, float radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(radius);
        if (strokeColor != 0x00000000) background.setStroke(1, strokeColor);
        return background;
    }

    private int color(int resource) {
        return ContextCompat.getColor(requireContext(), resource);
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
