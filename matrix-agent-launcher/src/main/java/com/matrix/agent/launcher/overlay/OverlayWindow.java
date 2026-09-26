package com.matrix.agent.launcher.overlay;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.RippleDrawable;
import com.matrix.agent.api.conversation.ConversationMessage;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import com.matrix.agent.api.handoff.HandoffProtocol;
import com.matrix.agent.launcher.R;

/** Android window mechanics only. Owner and conversation state live in the controller/presenter. */
public final class OverlayWindow implements AutoCloseable {
    public interface Actions {
        void draftChanged(String text);
        void loadOlderMessages();
        void send();
        void cancel();
        void returnToAgent();
        void dismiss();
        void changed();
        void windowFailed(RuntimeException failure);
    }
    private final java.util.function.LongConsumer firstDraw;
    private long visibleSince;
    private boolean firstDrawReported;
    private final Context context;
    private final WindowManager windows;
    private final Actions actions;
    private final FrameLayout root;
    private final TextView bubble, title, progress, notice, gate;
    private final LinearLayout panel, heading, body, composer, commands;
    private final PanelDragHandle dragHandle;
    private final TextView dragHint;
    private final OverlayConversationList conversationList;
    private final Button more;
    private PopupMenu actionsMenu;
    private boolean compact;
    private final EditText input;
    private final Button cancel, send;
    private final WindowManager.LayoutParams layout;
    private boolean attached, expanded, editing, hidden, applyingDraft;
    private boolean interactive = true;
    private int activity = -1;
    private int bubbleX, bubbleY, panelX, panelY;
    private boolean bubblePositionInitialized, panelPositionInitialized;
    private int imeBottom;
    private final int foreground, muted;

    public OverlayWindow(Context appContext, Actions actions) {
        this(appContext, actions, ignored -> {});
    }
    public OverlayWindow(Context appContext, Actions actions, java.util.function.LongConsumer firstDraw) {
        this.firstDraw = firstDraw;
        this.actions = actions;
        var display = appContext.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        Context displayContext = appContext.createDisplayContext(display);
        Context windowContext = Build.VERSION.SDK_INT >= 30
                ? displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                : displayContext;
        context = new ContextThemeWrapper(windowContext, R.style.Theme_MatrixLauncher);
        windows = context.getSystemService(WindowManager.class);
        foreground = color(R.color.overlay_text); muted = color(R.color.overlay_muted);
        root = new FrameLayout(context) {
            @Override public void onWindowFocusChanged(boolean hasFocus) {
                super.onWindowFocusChanged(hasFocus);
                if (hasFocus && editing) post(OverlayWindow.this::showKeyboard);
            }
            @Override public boolean dispatchKeyEventPreIme(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                    if (editing) finishEditing(); else collapse();
                    return true;
                }
                return super.dispatchKeyEventPreIme(event);
            }
        };
        root.getViewTreeObserver().addOnDrawListener(() -> {
            if (!attached || hidden || firstDrawReported) return;
            firstDrawReported = true;
            long duration = Math.max(0, android.os.SystemClock.elapsedRealtime() - visibleSince);
            // OnDraw is rendering evidence, not proof of compositor presentation or user visibility.
            // Never remove a tree listener during dispatch or perform diagnostics inside onDraw.
            root.post(() -> firstDraw.accept(duration));
        });
        root.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(expanded ? 20 : 28));
            }
        });
        root.setElevation(dp(12));
        bubble = new BubbleView(context);
        bubble.setText(context.getString(R.string.overlay_bubble_marker, "···"));
        bubble.setTextSize(16); bubble.setTextColor(color(R.color.overlay_on_header));
        bubble.setTypeface(null, Typeface.BOLD);
        bubble.setGravity(Gravity.CENTER);
        bubble.setBackground(background(color(R.color.overlay_header), 28, color(R.color.overlay_active)));
        bubble.setContentDescription(context.getString(R.string.overlay_bubble_description,
                context.getString(R.string.overlay_sync_task)));
        root.addView(bubble, new FrameLayout.LayoutParams(-1, -1));
        panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(background(color(R.color.overlay_surface), 20, color(R.color.overlay_border)));
        panel.setClipToOutline(true);
        panel.setVisibility(View.GONE);
        root.addView(panel, new FrameLayout.LayoutParams(-1, -1));

        heading = row();
        heading.setPadding(dp(14), dp(4), dp(6), dp(4));
        heading.setBackgroundColor(color(R.color.overlay_header));
        dragHandle = new PanelDragHandle(context);
        title = text(context.getString(R.string.overlay_title), 16, color(R.color.overlay_on_header));
        title.setTypeface(null, Typeface.BOLD);
        title.setMaxLines(1); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        dragHandle.addView(title);
        dragHint = text(context.getString(R.string.overlay_drag_hint), 11, color(R.color.overlay_header_muted));
        dragHint.setPadding(0, dp(3), 0, 0);
        dragHandle.addView(dragHint);
        heading.addView(dragHandle, new LinearLayout.LayoutParams(0, -2, 1));
        more = button(R.string.overlay_more, this::showActionsMenu, color(R.color.overlay_on_header), Color.TRANSPARENT);
        more.setVisibility(View.GONE);
        heading.addView(more);
        heading.addView(button(R.string.overlay_collapse, this::collapse, color(R.color.overlay_on_header), Color.TRANSPARENT));
        heading.addView(button(R.string.overlay_close, actions::dismiss, color(R.color.overlay_header_muted), Color.TRANSPARENT));
        panel.addView(heading);

        body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(12), dp(10), dp(12), dp(10));
        panel.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        progress = text(context.getString(R.string.overlay_sync_task), 12, color(R.color.overlay_active));
        progress.setTypeface(null, Typeface.BOLD);
        progress.setPadding(dp(10), dp(7), dp(10), dp(7));
        body.addView(progress, new LinearLayout.LayoutParams(-1, -2));

        conversationList = new OverlayConversationList(context, actions::loadOlderMessages);
        LinearLayout.LayoutParams conversationLayout = new LinearLayout.LayoutParams(-1, 0, 1);
        conversationLayout.topMargin = dp(8); conversationLayout.bottomMargin = dp(8);
        body.addView(conversationList, conversationLayout);
        notice = text("", 12, color(R.color.overlay_warning));
        notice.setPadding(dp(10), dp(6), dp(10), dp(6));
        notice.setBackground(background(color(R.color.overlay_warning_surface), 8, Color.TRANSPARENT));
        body.addView(notice);
        gate = text(context.getString(R.string.overlay_sync_activity), 11, muted);
        gate.setPadding(dp(2), dp(4), 0, dp(6));
        body.addView(gate);
        input = new DraftInput(context);
        input.setTextColor(foreground); input.setHintTextColor(muted);
        input.setHint(R.string.overlay_input_hint); input.setTextSize(14);
        input.setMaxLines(3); input.setMinLines(1);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        input.setPadding(dp(12), dp(8), dp(12), dp(8));
        styleInput();
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!applyingDraft) actions.draftChanged(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        input.setOnEditorActionListener((view, action, event) -> {
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) { actions.send(); return true; }
            return false;
        });
        composer = row();
        composer.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(composer, new LinearLayout.LayoutParams(-1, -2));
        commands = row();
        commands.setPadding(0, dp(6), 0, 0);
        commands.addView(button(R.string.overlay_return, actions::returnToAgent, color(R.color.overlay_active), Color.TRANSPARENT));
        cancel = button(R.string.overlay_cancel, actions::cancel, color(R.color.overlay_danger), Color.TRANSPARENT);
        commands.addView(cancel);
        Space spacer = new Space(context); commands.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));
        send = button(R.string.overlay_send, actions::send, color(R.color.overlay_card), color(R.color.overlay_send));
        send.setTypeface(null, Typeface.BOLD);
        commands.addView(send); body.addView(commands);
        layout = new WindowManager.LayoutParams(dp(56), dp(56),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        layout.gravity = Gravity.TOP | Gravity.LEFT;
        layout.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        layout.setTitle("Matrix Agent Overlay");
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (!editing || !attached) return;
            Rect visibleFrame = new Rect();
            root.getWindowVisibleDisplayFrame(visibleFrame);
            int obscured = Math.max(0, bounds().height() - visibleFrame.bottom);
            // Floating windows can receive IME insets relative to their own frame. The display
            // frame is the authority for keeping the composer and actions above the keyboard.
            int keyboardInset = obscured > dp(120) ? obscured : 0;
            if (keyboardInset != imeBottom) { imeBottom = keyboardInset; root.post(this::update); }
        });
        bubble.setOnClickListener(view -> expand());
    }

    private final class DraftInput extends androidx.appcompat.widget.AppCompatEditText {
        DraftInput(Context context) { super(context); }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN && !editing && !beginEditing()) return true;
            return editing && super.onTouchEvent(event);
        }
        @Override public boolean performClick() {
            if (!beginEditing()) return false;
            super.performClick(); return true;
        }
    }

    /** Only this dedicated title region drags the panel. Sibling controls and body keep their events. */
    private final class PanelDragHandle extends LinearLayout {
        private final WindowDrag drag = new WindowDrag(true);
        PanelDragHandle(Context context) {
            super(context);
            setOrientation(VERTICAL); setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(dp(52)); setClickable(true);
            setContentDescription(context.getString(R.string.overlay_drag_description));
        }
        @Override public boolean performClick() { return interactive && super.performClick(); }
        @Override public boolean onTouchEvent(MotionEvent event) { return drag.touch(this, event); }
    }
    private final class BubbleView extends androidx.appcompat.widget.AppCompatTextView {
        private final WindowDrag drag = new WindowDrag(false);
        BubbleView(Context context) { super(context); }
        @Override public boolean performClick() { return interactive && super.performClick(); }
        @Override public boolean onTouchEvent(MotionEvent event) { return drag.touch(this, event); }
    }
    private final class WindowDrag {
        private final boolean panelTarget;
        private final OverlayDragGesture gesture = new OverlayDragGesture(
                ViewConfiguration.get(context).getScaledTouchSlop());
        WindowDrag(boolean panelTarget) { this.panelTarget = panelTarget; }
        boolean touch(View target, MotionEvent event) {
            if (!interactive || hidden || expanded != panelTarget) { gesture.cancel(); return false; }
            return switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    gesture.begin(event.getRawX(), event.getRawY(), layout.x, layout.y);
                    yield true;
                }
                case MotionEvent.ACTION_MOVE -> { move(event); yield true; }
                case MotionEvent.ACTION_UP -> {
                    move(event);
                    var completion = gesture.finish();
                    if (completion == OverlayDragGesture.Completion.TAP) target.performClick();
                    else if (completion == OverlayDragGesture.Completion.DRAG && !panelTarget) {
                        bubbleX = layout.x < bounds().width() / 2 ? dp(8) : bounds().width() - dp(64);
                        update();
                    }
                    yield true;
                }
                // Do not transfer a gesture to another finger or turn a cancelled drag into a tap.
                case MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                    gesture.cancel(); yield true;
                }
                default -> true;
            };
        }
        private void move(MotionEvent event) {
            var position = gesture.move(event.getRawX(), event.getRawY());
            if (position == null) return;
            if (panelTarget) { panelX = position.x(); panelY = position.y(); }
            else { bubbleX = position.x(); bubbleY = position.y(); }
            update();
            // Persist the clamped position, so rendering or reopening cannot restore off-screen coordinates.
            if (panelTarget) { panelX = layout.x; panelY = layout.y; }
            else { bubbleX = layout.x; bubbleY = layout.y; }
        }
    }

    public boolean attached() { return attached; }
    public boolean visible() { return attached && !hidden; }
    public boolean expanded() { return expanded; }
    public boolean editing() { return editing; }
    /** A mounted candidate cannot issue commands against the previous owner before ACK commit. */
    public void setInteractive(boolean value) {
        interactive = value;
        root.setImportantForAccessibility(value ? View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        update();
    }
    public void attach(boolean hidden) {
        this.hidden = hidden;
        if (!hidden) visibleSince = android.os.SystemClock.elapsedRealtime();
        configure();
        windows.addView(root, layout); attached = true; actions.changed();
    }
    public void setHidden(boolean value) {
        if (hidden == value) return;
        if (value) { dismissActionsMenu(); finishEditing(); }
        hidden = value;
        if (!value) visibleSince = android.os.SystemClock.elapsedRealtime();
        update(); actions.changed();
    }
    public void expand() {
        expanded = true; root.invalidateOutline();
        bubble.setVisibility(View.GONE); panel.setVisibility(View.VISIBLE);
        update(); actions.changed();
    }
    public void collapse() {
        dismissActionsMenu(); finishEditing(); expanded = false; root.invalidateOutline();
        bubble.setVisibility(View.VISIBLE); panel.setVisibility(View.GONE);
        update(); actions.changed();
    }
    private boolean beginEditing() {
        if (!interactive) return false;
        if (editing) return true;
        if (activity != HandoffProtocol.IDLE) {
            gate.announceForAccessibility(gate.getText()); return false;
        }
        editing = true;
        gate.setText(R.string.overlay_editing);
        styleInput();
        input.requestFocus(); update();
        if (root.hasWindowFocus()) input.post(this::showKeyboard);
        return true;
    }
    private void showKeyboard() {
        if (!editing || !attached || !root.hasWindowFocus()) return;
        input.requestFocus();
        context.getSystemService(InputMethodManager.class).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
    }
    public void finishEditing() {
        if (!editing) return;
        context.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(input.getWindowToken(), 0);
        editing = false; input.clearFocus(); imeBottom = 0; styleInput(); update();
    }
    public void render(OverlayConversationPresenter.State state, String draft, int activity, boolean sending) {
        this.activity = activity;
        title.setText(state.title());
        progress.setText(state.connected() ? state.progress() : context.getString(R.string.overlay_reconnecting));
        conversationList.render(state); notice.setText(state.notice());
        notice.setVisibility(state.notice().isBlank() ? View.GONE : View.VISIBLE);
        styleProgress(state);
        gate.setText(editing ? R.string.overlay_editing
                : activity == HandoffProtocol.AUTOMATION ? R.string.overlay_automation
                : activity == HandoffProtocol.IDLE ? R.string.overlay_idle : R.string.overlay_sync_activity);
        cancel.setEnabled(state.connected() && !OverlayConversationPresenter.terminal(state.status()) && !state.cancelling());
        if (actionsMenu != null) actionsMenu.getMenu().findItem(R.id.overlay_action_cancel).setEnabled(cancel.isEnabled());
        send.setEnabled(state.connected() && !sending && !draft.isBlank());
        send.setText(sending ? R.string.overlay_sending : R.string.overlay_send);
        if (!input.getText().toString().equals(draft)) {
            applyingDraft = true; input.setText(draft); input.setSelection(input.length()); applyingDraft = false;
        }
        String marker = switch (state.status()) {
            case ConversationMessage.STATUS_COMPLETED -> "✓";
            case ConversationMessage.STATUS_FAILED, ConversationMessage.STATUS_REJECTED,
                    ConversationMessage.STATUS_EXECUTION_UNKNOWN -> "!";
            case ConversationMessage.STATUS_CANCELLED -> "–";
            default -> "···";
        };
        bubble.setText(context.getString(R.string.overlay_bubble_marker, marker));
        bubble.setContentDescription(context.getString(R.string.overlay_bubble_description, state.progress()));
    }
    public void update() {
        if (!attached) return;
        configure();
        try { windows.updateViewLayout(root, layout); }
        catch (RuntimeException failure) { actions.windowFailed(failure); }
    }
    private void configure() {
        Rect area = bounds();
        if (!bubblePositionInitialized) {
            bubbleX = area.width() - dp(64); bubbleY = area.height() / 3;
            bubblePositionInitialized = true;
        }
        layout.width = expanded ? Math.min(dp(420), (int) (area.width() * .88f)) : dp(56);
        layout.height = expanded ? OverlayGeometry.panelHeight(area.height(), imeBottom, dp(48)) : dp(56);
        if (expanded) applyCompactLayout(layout.height < dp(320));
        if (expanded && !panelPositionInitialized) {
            panelX = (area.width() - layout.width) / 2; panelY = bubbleY;
            panelPositionInitialized = true;
        }
        layout.x = OverlayGeometry.clampPosition(expanded ? panelX : bubbleX,
                area.width(), layout.width, dp(8), dp(8));
        layout.y = OverlayGeometry.clampPosition(expanded ? panelY : bubbleY,
                Math.max(0, area.height() - imeBottom), layout.height, dp(24), dp(24));
        layout.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | (editing && !hidden ? 0 : WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                | (hidden || !interactive ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : 0);
        root.setVisibility(hidden ? View.INVISIBLE : View.VISIBLE);
    }
    private Rect bounds() {
        if (Build.VERSION.SDK_INT >= 30) return windows.getMaximumWindowMetrics().getBounds();
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        windows.getDefaultDisplay().getRealMetrics(metrics);
        return new Rect(0, 0, metrics.widthPixels, metrics.heightPixels);
    }
    private LinearLayout row() { LinearLayout value = new LinearLayout(context); value.setGravity(Gravity.CENTER_VERTICAL); return value; }
    private TextView text(String value, int size, int color) { TextView view = new TextView(context); view.setText(value); view.setTextSize(size); view.setTextColor(color); return view; }
    /** Short windows keep a readable conversation viewport and the entire composer visible.
     * Secondary commands move to a titled menu; the input itself is never detached or recreated. */
    private void applyCompactLayout(boolean value) {
        if (compact == value) return;
        compact = value;
        dismissActionsMenu();
        dragHint.setVisibility(value ? View.GONE : View.VISIBLE);
        dragHandle.setMinimumHeight(dp(value ? 48 : 52));
        heading.setPadding(dp(14), dp(value ? 0 : 4), dp(6), dp(value ? 0 : 4));
        body.setPadding(dp(12), dp(value ? 6 : 10), dp(12), dp(value ? 6 : 10));
        progress.setPadding(dp(10), dp(value ? 4 : 7), dp(10), dp(value ? 4 : 7));
        progress.setMaxLines(value ? 1 : Integer.MAX_VALUE);
        progress.setEllipsize(value ? android.text.TextUtils.TruncateAt.END : null);
        var conversationLayout = (LinearLayout.LayoutParams) conversationList.getLayoutParams();
        conversationLayout.topMargin = dp(value ? 4 : 8); conversationLayout.bottomMargin = dp(value ? 4 : 8);
        conversationList.setLayoutParams(conversationLayout);
        gate.setMaxLines(value ? 1 : Integer.MAX_VALUE);
        gate.setEllipsize(value ? android.text.TextUtils.TruncateAt.END : null);
        gate.setPadding(dp(2), dp(value ? 2 : 4), 0, dp(value ? 2 : 6));
        notice.setMaxLines(value ? 1 : Integer.MAX_VALUE);
        notice.setEllipsize(value ? android.text.TextUtils.TruncateAt.END : null);
        input.setMaxLines(value ? 1 : 3);
        more.setVisibility(value ? View.VISIBLE : View.GONE);
        commands.setVisibility(value ? View.GONE : View.VISIBLE);
        ((android.view.ViewGroup) send.getParent()).removeView(send);
        LinearLayout.LayoutParams sendLayout = new LinearLayout.LayoutParams(-2, dp(48));
        sendLayout.leftMargin = value ? dp(8) : 0;
        (value ? composer : commands).addView(send, sendLayout);
    }
    private void showActionsMenu() {
        if (!interactive || !attached || hidden) return;
        dismissActionsMenu();
        PopupMenu menu = new PopupMenu(context, more);
        menu.getMenu().add(0, R.id.overlay_action_return, 0, R.string.overlay_return);
        menu.getMenu().add(0, R.id.overlay_action_cancel, 1, R.string.overlay_cancel).setEnabled(cancel.isEnabled());
        menu.setOnMenuItemClickListener(item -> {
            if (!interactive || !attached || hidden) return false;
            if (item.getItemId() == R.id.overlay_action_return) actions.returnToAgent();
            else if (item.getItemId() == R.id.overlay_action_cancel && cancel.isEnabled()) actions.cancel();
            return true;
        });
        menu.setOnDismissListener(ignored -> { if (actionsMenu == menu) actionsMenu = null; });
        actionsMenu = menu;
        menu.show();
    }
    private void dismissActionsMenu() {
        if (actionsMenu != null) { actionsMenu.dismiss(); actionsMenu = null; }
    }
    private void styleInput() {
        input.setBackground(background(color(editing ? R.color.overlay_card : R.color.overlay_input),
                12, color(editing ? R.color.overlay_active : R.color.overlay_border)));
    }
    private void styleProgress(OverlayConversationPresenter.State state) {
        int ink = R.color.overlay_active, surface = R.color.overlay_active_surface;
        if (!state.connected() || state.status() == ConversationMessage.STATUS_EXECUTION_UNKNOWN) {
            ink = R.color.overlay_warning; surface = R.color.overlay_warning_surface;
        } else if (state.status() == ConversationMessage.STATUS_COMPLETED) {
            ink = R.color.overlay_success; surface = R.color.overlay_success_surface;
        } else if (state.status() == ConversationMessage.STATUS_FAILED || state.status() == ConversationMessage.STATUS_REJECTED) {
            ink = R.color.overlay_danger; surface = R.color.overlay_danger_surface;
        } else if (state.status() == ConversationMessage.STATUS_CANCELLED) {
            ink = R.color.overlay_muted; surface = R.color.overlay_input;
        }
        progress.setTextColor(color(ink));
        progress.setBackground(background(color(surface), 9, Color.TRANSPARENT));
    }
    private Button button(int label, Runnable action, int ink, int fill) {
        Button view = new Button(context); view.setText(label); view.setTextSize(12);
        int[][] states = { new int[]{-android.R.attr.state_enabled}, new int[]{} };
        view.setTextColor(new ColorStateList(states, new int[]{muted, ink}));
        view.setAllCaps(false); view.setMinWidth(dp(48)); view.setMinimumWidth(dp(48));
        view.setMinHeight(dp(48)); view.setMinimumHeight(dp(48));
        view.setPadding(dp(8), 0, dp(8), 0);
        GradientDrawable shape = background(fill, 10, Color.TRANSPARENT);
        shape.setColor(new ColorStateList(states, new int[]{fill == Color.TRANSPARENT
                ? Color.TRANSPARENT : color(R.color.overlay_disabled), fill}));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(40,
                Color.red(ink), Color.green(ink), Color.blue(ink))), shape, null));
        view.setOnClickListener(ignored -> { if (interactive) action.run(); }); return view;
    }
    private GradientDrawable background(int fill, int radius, int stroke) {
        GradientDrawable value = new GradientDrawable(); value.setColor(fill); value.setCornerRadius(dp(radius));
        if (stroke != Color.TRANSPARENT) value.setStroke(dp(1), stroke);
        return value;
    }
    private int color(int resource) { return context.getColor(resource); }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    @Override public void close() {
        dismissActionsMenu(); finishEditing();
        if (attached) {
            attached = false;
            try { windows.removeViewImmediate(root); } catch (RuntimeException ignored) { }
        }
    }
}
