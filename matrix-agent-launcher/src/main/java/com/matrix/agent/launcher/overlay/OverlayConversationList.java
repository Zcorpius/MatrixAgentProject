package com.matrix.agent.launcher.overlay;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.LinearLayout;
import com.matrix.agent.launcher.BuildConfig;
import com.matrix.agent.launcher.presentation.ConversationTraceRenderer;
import android.widget.TextView;
import android.widget.Toast;
import com.matrix.agent.launcher.R;
import com.matrix.agent.launcher.presentation.ConversationMessageRenderer;
import com.matrix.agent.launcher.presentation.ConversationViewModel.UiMessage;
import java.util.List;

/** Virtualized conversation rows with stable sequence identities and explicit scroll anchoring. */
final class OverlayConversationList extends FrameLayout {
    private final ListView list;
    private final TextView empty;
    private final Button older;
    private final Rows adapter;
    private List<UiMessage> messages = List.of();
    private long renderVersion;
    private boolean more, loading, connected, pendingFollow;
    private String historyError = "";

    OverlayConversationList(Context context, Runnable loadOlder) {
        super(context);
        list = new ListView(context);
        list.setDivider(null); list.setSelector(android.R.color.transparent);
        list.setTranscriptMode(ListView.TRANSCRIPT_MODE_DISABLED);
        list.setClipToPadding(false);
        list.setPadding(0, dp(2), 0, dp(2));
        older = new Button(context);
        older.setTextSize(12); older.setTextColor(context.getColor(R.color.overlay_active));
        older.setBackgroundColor(Color.TRANSPARENT);
        older.setOnClickListener(ignored -> loadOlder.run());
        FrameLayout header = new FrameLayout(context);
        header.addView(older, new FrameLayout.LayoutParams(-1, -2));
        list.addHeaderView(header, null, false);
        adapter = new Rows(context); list.setAdapter(adapter);
        addView(list, new FrameLayout.LayoutParams(-1, -1));
        empty = new TextView(context);
        empty.setTextColor(context.getColor(R.color.overlay_muted)); empty.setTextSize(14);
        empty.setGravity(android.view.Gravity.CENTER);
        empty.setOnClickListener(ignored -> loadOlder.run());
        addView(empty, new FrameLayout.LayoutParams(-1, -1));
        list.setEmptyView(empty);
        list.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(android.widget.AbsListView view, int state) {
                if (state == SCROLL_STATE_TOUCH_SCROLL) { pendingFollow = false; ++renderVersion; }
            }
            @Override public void onScroll(android.widget.AbsListView view, int first, int visible, int total) {
                View last = view.getChildAt(view.getChildCount() - 1);
                if (first + visible >= total && last != null && last.getBottom() <= view.getHeight() + dp(24))
                    pendingFollow = false;
            }
        });
        list.addOnLayoutChangeListener((view, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            if (r - l != oldR - oldL) adapter.notifyDataSetChanged();
        });
    }

    void render(OverlayConversationPresenter.State state) {
        List<UiMessage> next = state.messages();
        if (messages.equals(next) && more == state.hasMoreHistory()
                && loading == state.loadingHistory() && connected == state.connected()
                && historyError.equals(state.historyError())) return;
        int first = list.getFirstVisiblePosition();
        View firstView = list.getChildAt(0);
        int offset = firstView == null ? 0 : firstView.getTop();
        String anchor = first > 0 && first <= messages.size() ? messages.get(first - 1).messageId() : null;
        View lastView = list.getChildAt(list.getChildCount() - 1);
        boolean atBottom = list.getLastVisiblePosition() >= messages.size()
                && (lastView == null || lastView.getBottom() <= list.getHeight() + dp(24));
        boolean prepended = !messages.isEmpty() && !next.isEmpty()
                && next.get(0).sequence() < messages.get(0).sequence();
        boolean follow = messages.isEmpty() || (!prepended && (pendingFollow || atBottom));
        pendingFollow = follow;
        messages = List.copyOf(next);
        more = state.hasMoreHistory(); loading = state.loadingHistory(); historyError = state.historyError();
        connected = state.connected();
        older.setVisibility(more || loading || !historyError.isEmpty() ? VISIBLE : GONE);
        older.setEnabled(state.connected() && !loading);
        older.setText(loading ? R.string.overlay_history_loading : historyError.isEmpty()
                ? R.string.overlay_history_older : R.string.overlay_history_retry);
        empty.setText(loading ? R.string.overlay_history_loading : historyError.isEmpty()
                ? R.string.overlay_history_empty : R.string.overlay_history_retry);
        adapter.notifyDataSetChanged();
        long version = ++renderVersion;
        list.post(() -> {
            if (version != renderVersion) return;
            if (follow) list.setSelection(messages.size()); // header occupies position 0
            else if (anchor != null) {
                for (int i = 0; i < messages.size(); i++) {
                    if (anchor.equals(messages.get(i).messageId())) {
                        list.setSelectionFromTop(i + 1, offset); break;
                    }
                }
            } else list.setSelectionFromTop(0, offset);
        });
    }
    private final class Rows extends BaseAdapter {
        private final ConversationMessageRenderer renderer;
        Rows(Context context) { renderer = new ConversationMessageRenderer(context); }
        @Override public int getCount() { return messages.size(); }
        @Override public UiMessage getItem(int position) { return messages.get(position); }
        @Override public long getItemId(int position) { return getItem(position).sequence(); }
        @Override public boolean hasStableIds() { return true; }
        @Override public boolean isEnabled(int position) { return false; }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public View getView(int position, View convert, ViewGroup parent) {
            UiMessage message = getItem(position);
            int width = list.getWidth() > 0 ? list.getWidth()
                    : Math.min(dp(420), (int) (getResources().getDisplayMetrics().widthPixels * .88f)) - dp(24);
            Row holder = convert != null && convert.getTag() instanceof Row row ? row : new Row();
            if (!message.equals(holder.message) || holder.width != width) {
                holder.container.removeAllViews();
                LinearLayout content = new LinearLayout(getContext());
                content.setOrientation(LinearLayout.VERTICAL);
                content.addView(renderer.create(message, width, this::copy));
                if (BuildConfig.MATRIX_DEBUG_TRACE_UI && !message.debugTraces().isEmpty())
                    content.addView(new ConversationTraceRenderer(getContext()).create(message, width));
                holder.container.addView(content);
                holder.message = message; holder.width = width;
            }
            return holder.container;
        }
        private void copy(UiMessage message) {
            getContext().getSystemService(ClipboardManager.class)
                    .setPrimaryClip(ClipData.newPlainText("conversation", message.text()));
            Toast.makeText(getContext(), R.string.overlay_message_copied, Toast.LENGTH_SHORT).show();
        }
    }
    private final class Row {
        final FrameLayout container = new FrameLayout(getContext());
        UiMessage message;
        int width;
        Row() {
            container.setLayoutParams(new ListView.LayoutParams(-1, -2));
            container.setTag(this);
        }
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
