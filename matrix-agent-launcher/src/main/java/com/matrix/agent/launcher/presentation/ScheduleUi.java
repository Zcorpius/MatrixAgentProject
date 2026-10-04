package com.matrix.agent.launcher.presentation;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.matrix.agent.launcher.R;
import com.matrix.agent.launcher.presentation.theme.LauncherThemePreferences;

/** Small, theme-aware vocabulary shared by every task-center state. */
final class ScheduleUi {
    private final Context context;
    private final float density;
    final int paper, surface, ink, muted, accent, accentDeep, onAccent, selected, border, danger;

    ScheduleUi(Context context) {
        this.context = context;
        density = context.getResources().getDisplayMetrics().density;
        paper = color(R.attr.matrix_paper);
        surface = color(R.attr.matrix_paper_high);
        ink = color(R.attr.matrix_ink);
        muted = color(R.attr.matrix_ink_muted);
        accent = color(R.attr.matrix_accent);
        accentDeep = color(R.attr.matrix_accent_deep);
        onAccent = color(R.attr.matrix_on_accent);
        selected = color(R.attr.matrix_selected);
        border = color(R.attr.matrix_divider);
        danger = color(R.attr.matrix_danger);
    }

    int dp(int value) { return Math.round(value * density); }
    private int color(int attribute) { return LauncherThemePreferences.color(context, attribute); }

    LinearLayout column() {
        LinearLayout result = new LinearLayout(context);
        result.setOrientation(LinearLayout.VERTICAL);
        return result;
    }

    LinearLayout row() {
        LinearLayout result = new LinearLayout(context);
        result.setOrientation(LinearLayout.HORIZONTAL);
        result.setGravity(Gravity.CENTER_VERTICAL);
        return result;
    }

    LinearLayout.LayoutParams top(int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(margin);
        return params;
    }

    LinearLayout.LayoutParams weighted(int marginStart) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.leftMargin = dp(marginStart);
        return params;
    }

    TextView label(String value, int size, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setLineSpacing(dp(3), 1f);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    TextView title(String value, int size) {
        TextView view = label(value, size, ink, true);
        view.setTypeface(Typeface.create("serif", Typeface.BOLD));
        return view;
    }

    TextView eyebrow(String value) {
        TextView view = label(value, 11, accentDeep, true);
        view.setLetterSpacing(.13f);
        return view;
    }

    LinearLayout card() {
        LinearLayout card = column();
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(ripple(surface, border, 20));
        return card;
    }

    TextView action(String value, boolean primary, boolean enabled, Runnable onClick) {
        TextView action = label(value, 14, primary ? onAccent : ink, true);
        action.setGravity(Gravity.CENTER);
        action.setMinHeight(dp(50));
        action.setPadding(dp(16), dp(11), dp(16), dp(11));
        action.setBackground(ripple(primary ? accent : surface, primary ? accent : border, 15));
        action.setEnabled(enabled);
        action.setAlpha(enabled ? 1f : .48f);
        action.setOnClickListener(view -> onClick.run());
        return action;
    }

    TextView quietAction(String value, boolean enabled, Runnable onClick) {
        TextView action = label(value, 13, accentDeep, true);
        action.setGravity(Gravity.CENTER_VERTICAL);
        action.setMinHeight(dp(44));
        action.setPadding(dp(8), dp(8), dp(8), dp(8));
        action.setEnabled(enabled);
        action.setAlpha(enabled ? 1f : .48f);
        action.setOnClickListener(view -> onClick.run());
        return action;
    }

    TextView dangerAction(String value, boolean enabled, Runnable onClick) {
        TextView action = action(value, false, enabled, onClick);
        action.setTextColor(danger);
        return action;
    }

    RippleDrawable fieldBackground() { return ripple(surface, border, 12); }

    GradientDrawable dialogBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(paper);
        background.setCornerRadius(dp(20));
        return background;
    }

    void divider(LinearLayout parent, int top) {
        View line = new View(context);
        line.setBackgroundColor(border);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        params.topMargin = dp(top);
        parent.addView(line, params);
    }

    private RippleDrawable ripple(int fill, int stroke, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        shape.setStroke(dp(1), stroke);
        return new RippleDrawable(ColorStateList.valueOf(selected), shape, null);
    }
}
