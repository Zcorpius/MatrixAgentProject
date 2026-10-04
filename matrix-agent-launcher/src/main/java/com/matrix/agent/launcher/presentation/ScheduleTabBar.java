package com.matrix.agent.launcher.presentation;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.EnumMap;
import java.util.function.Consumer;

/** Four equal-width destinations in one continuous navigation surface. */
final class ScheduleTabBar extends LinearLayout {
    private final ScheduleUi ui;
    private final EnumMap<ScheduleViewModel.Tab, FrameLayout> cells = new EnumMap<>(ScheduleViewModel.Tab.class);
    private final EnumMap<ScheduleViewModel.Tab, TextView> labels = new EnumMap<>(ScheduleViewModel.Tab.class);
    private final EnumMap<ScheduleViewModel.Tab, View> indicators = new EnumMap<>(ScheduleViewModel.Tab.class);

    ScheduleTabBar(Context context, ScheduleUi ui, Consumer<ScheduleViewModel.Tab> onSelect) {
        super(context);
        this.ui = ui;
        setOrientation(HORIZONTAL);
        setBackgroundColor(ui.surface);
        add(ScheduleViewModel.Tab.PLANS, "计划", onSelect);
        add(ScheduleViewModel.Tab.RUNS, "运行记录", onSelect);
        add(ScheduleViewModel.Tab.TEMPLATES, "模板", onSelect);
        add(ScheduleViewModel.Tab.INSTANT, "临时任务", onSelect);
    }

    void select(ScheduleViewModel.Tab selected) {
        for (ScheduleViewModel.Tab tab : ScheduleViewModel.Tab.values()) {
            boolean active = tab == selected;
            FrameLayout cell = cells.get(tab);
            TextView label = labels.get(tab);
            cell.setSelected(active);
            cell.setContentDescription(label.getText() + (active ? "，已选中" : ""));
            label.setTextColor(active ? ui.accentDeep : ui.ink);
            label.setAlpha(active ? 1f : .68f);
            label.setTypeface(Typeface.DEFAULT, active ? Typeface.BOLD : Typeface.NORMAL);
            indicators.get(tab).setVisibility(active ? VISIBLE : INVISIBLE);
        }
    }

    void setTemplatesVisible(boolean visible) {
        cells.get(ScheduleViewModel.Tab.TEMPLATES).setVisibility(visible ? VISIBLE : GONE);
    }

    private void add(ScheduleViewModel.Tab tab, String title, Consumer<ScheduleViewModel.Tab> onSelect) {
        FrameLayout cell = new FrameLayout(getContext());
        cell.setForeground(new RippleDrawable(ColorStateList.valueOf(ui.selected),
                new ColorDrawable(Color.TRANSPARENT), null));
        cell.setOnClickListener(view -> onSelect.accept(tab));
        cell.setFocusable(true);

        TextView label = ui.label(title, 13, ui.ink, false);
        label.setAlpha(.68f);
        label.setGravity(Gravity.CENTER);
        cell.addView(label, new FrameLayout.LayoutParams(-1, -1));

        View indicator = new View(getContext());
        indicator.setBackgroundColor(ui.accent);
        FrameLayout.LayoutParams indicatorParams = new FrameLayout.LayoutParams(-1, ui.dp(2), Gravity.BOTTOM);
        indicatorParams.leftMargin = ui.dp(12);
        indicatorParams.rightMargin = ui.dp(12);
        cell.addView(indicator, indicatorParams);

        addView(cell, new LinearLayout.LayoutParams(0, ui.dp(52), 1));
        cells.put(tab, cell);
        labels.put(tab, label);
        indicators.put(tab, indicator);
    }
}
