package com.matrix.agent.launcher.presentation;

import static com.matrix.agent.api.schedule.ScheduleCodes.*;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import com.matrix.agent.api.schedule.*;
import com.matrix.agent.launcher.LauncherActivity;
import com.matrix.agent.launcher.R;

/** Task center: persistent plans and runs, with the existing instant-task surface kept intact. */
public final class ScheduleFragment extends Fragment {
    private ScheduleViewModel model;
    private LinearLayout content;
    private TextView notice;
    private FrameLayout instant;
    private ScrollView scroll;
    private ScheduleUi ui;
    private ScheduleTabBar tabBar;
    private String renderedKey = "";
    public static ScheduleFragment forHistory() { var fragment = new ScheduleFragment(); Bundle args = new Bundle(); args.putBoolean("history", true); fragment.setArguments(args); return fragment; }
    public static ScheduleFragment forRun(String id) { var fragment = new ScheduleFragment(); Bundle args = new Bundle(); args.putString("run", id); fragment.setArguments(args); return fragment; }
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent, @Nullable Bundle state) {
        model = new ViewModelProvider(requireActivity(), activity().viewModelFactory()).get(ScheduleViewModel.class);
        if (state != null) model.restoreUiState(state.getBundle("scheduleUi"));
        ui = new ScheduleUi(requireContext());
        LinearLayout root = column(); root.setBackgroundColor(ui.paper);
        tabBar = new ScheduleTabBar(requireContext(), ui, model::select);
        root.addView(tabBar);
        View tabDivider = new View(requireContext());
        tabDivider.setBackgroundColor(ui.border);
        root.addView(tabDivider, new LinearLayout.LayoutParams(-1, dp(1)));
        notice = ui.label("", 13, ui.muted, false);
        notice.setPadding(dp(22), dp(3), dp(22), dp(9));
        root.addView(notice);
        scroll = new ScrollView(requireContext());
        scroll.setFillViewport(true);
        content = column(); content.setPadding(dp(20), dp(8), dp(20), dp(36)); scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        instant = new FrameLayout(requireContext()); instant.setId(R.id.schedule_instant_container);
        root.addView(instant, new LinearLayout.LayoutParams(-1, 0, 1));
        return root;
    }
    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), new androidx.activity.OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (!model.back()) { setEnabled(false); requireActivity().getOnBackPressedDispatcher().onBackPressed(); setEnabled(true); }
            }
        });
        model.state().observe(getViewLifecycleOwner(), this::render);
        new ViewModelProvider(requireActivity(), activity().viewModelFactory()).get(LauncherViewModel.class)
                .connectionState().observe(getViewLifecycleOwner(), ignored -> model.refresh());
        if (getArguments() != null && getArguments().containsKey("run")) {
            String id = getArguments().getString("run"); getArguments().remove("run"); model.showRun(id);
        }
        if (getArguments() != null && getArguments().getBoolean("history")) { getArguments().remove("history"); model.select(ScheduleViewModel.Tab.RUNS); }
        model.refresh();
    }
    private void render(ScheduleViewModel.State state) {
        String message = !state.error().isEmpty() ? state.error() : state.loading() ? "正在同步计划…" : model.connected() ? "" : "Host 未连接，计划状态暂不可用";
        notice.setText(message);
        notice.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
        notice.setTextColor(state.error().isEmpty() ? ui.muted : ui.danger);
        tabBar.select(state.tab());
        tabBar.setTemplatesVisible(model.supports(com.matrix.agent.api.common.MatrixServiceConstants.FEATURE_SCHEDULE_WORKFLOW));
        boolean temporary = state.tab() == ScheduleViewModel.Tab.INSTANT;
        scroll.setVisibility(temporary ? View.GONE : View.VISIBLE); instant.setVisibility(temporary ? View.VISIBLE : View.GONE);
        if (temporary) {
            if (getChildFragmentManager().findFragmentById(instant.getId()) == null) getChildFragmentManager().beginTransaction().replace(instant.getId(), new AgentTaskFragment()).commit();
            return;
        }
        String key = state.detail() == null ? state.tab().name() + state.selectedPlan() : state.detail().run().runId;
        if (!renderedKey.isEmpty()) model.scrollPosition(renderedKey, scroll.getScrollY());
        renderedKey = key;
        content.removeAllViews();
        ScrollView target = scroll;
        target.post(() -> { if (target == scroll && key.equals(renderedKey)) target.scrollTo(0, model.scrollPosition(key)); });
        if (state.snapshot() == null) {
            pageHeader("计划", "把重要的事，安排妥当", "计划与执行记录会在连接 Host 后显示。", true);
            emptyState("等待连接", "连接 Host 后可查看计划、运行记录和模板。", null);
            return;
        }
        if (state.detail() != null) { renderRun(state.detail()); return; }
        switch (state.tab()) {
            case PLANS -> {
                if (!state.selectedPlan().isEmpty()) {
                    var detail = model.planDetail();
                    if (detail != null && detail.plan().scheduleId.equals(state.selectedPlan())) renderPlan(detail.plan());
                    else {
                        content.addView(quietAction("‹  返回计划", () -> model.select(ScheduleViewModel.Tab.PLANS)));
                        content.addView(ui.title("计划详情", 25), ui.top(12));
                        content.addView(ui.label(state.error().isEmpty() ? "正在读取计划及运行历史…" : "无法读取此计划，请查看上方原因并重试。", 14, ui.muted, false), ui.top(8));
                    }
                    return;
                }
                pageHeader("计划", "你的计划", "按时间安排目标，之后交给 Host 持续管理。", true);
                readiness(state.snapshot().readiness());
                content.addView(primaryAction("＋  新建计划", () -> ScheduleEditorDialog.create(null, null).show(getParentFragmentManager(), "schedule-editor")), ui.top(24));
                if (model.supports(com.matrix.agent.api.common.MatrixServiceConstants.FEATURE_CALENDAR_DOMAIN))
                    content.addView(quietAction("连接日历与日程  ↗", () -> new ScheduleCalendarDialog().show(getParentFragmentManager(), "schedule-calendar")), ui.top(4));
                section("计划列表", state.snapshot().plans().items.size() + " 项");
                filter(new String[]{"全部计划", "启用", "暂停", "需要处理"}, state.tab());
                var plans = new java.util.ArrayList<>(state.snapshot().plans().items);
                plans.sort(java.util.Comparator.comparingLong(plan -> plan.nextDueAt <= 0 ? Long.MAX_VALUE : plan.nextDueAt));
                int visiblePlans = 0;
                for (ScheduleInfo plan : plans) {
                    int selected = model.filter(state.tab());
                    if (selected == 1 && plan.state != ACTIVE || selected == 2 && plan.state != PAUSED || selected == 3 && plan.health != BLOCKED) continue;
                    visiblePlans++;
                    LinearLayout card = card(); card.addView(ui.eyebrow(ScheduleLabels.plan(plan)));
                    card.addView(ui.title(plan.spec.title, 20), ui.top(7));
                    TextView goal = text(plan.spec.action.text, 14); goal.setMaxLines(2);
                    goal.setTextColor(ui.muted); goal.setEllipsize(android.text.TextUtils.TruncateAt.END); card.addView(goal, ui.top(7));
                    state.snapshot().runs().items.stream().filter(run -> run.scheduleId.equals(plan.scheduleId)).findFirst()
                            .ifPresent(run -> card.addView(ui.label("最近运行  " + ScheduleLabels.run(run.state), 12, ui.muted, false), ui.top(9)));
                    ui.divider(card, 15);
                    card.addView(ui.label("下次执行  " + ScheduleLabels.time(plan.nextDueAt) + "     ›", 13, ui.ink, true), ui.top(12));
                    if (!plan.reason.isEmpty()) card.addView(ui.label("需要处理  " + ScheduleLabels.reason(plan.reason), 12, ui.danger, false), ui.top(8));
                    card.setOnClickListener(v -> model.showPlan(plan.scheduleId)); content.addView(card, ui.top(10));
                }
                if (!state.snapshot().plans().nextCursor.isEmpty()) content.addView(button("加载更多计划", () -> model.more(true)), ui.top(12));
                if (visiblePlans == 0) emptyState(plans.isEmpty() ? "还没有计划" : "没有符合条件的计划",
                        plans.isEmpty() ? "创建提醒或定时目标，预览执行时间后再启用。" : "换一个筛选条件，查看其他计划。", null);
            }
            case RUNS -> {
                pageHeader("运行记录", "每一次执行，都有迹可循", "查看受理、执行和交付的完整状态。", true);
                section("运行记录", state.snapshot().runs().items.size() + " 项");
                filter(new String[]{"全部记录", "未结束", "成功", "部分完成", "失败", "错过", "待核验"}, state.tab());
                int visibleRuns = 0;
                for (ScheduleRunInfo run : state.snapshot().runs().items) {
                    int selected = model.filter(state.tab());
                    if (selected == 1 && terminalRun(run.state) || selected == 2 && run.state != SUCCEEDED
                            || selected == 3 && run.state != PARTIAL || selected == 4 && run.state != FAILED
                            || selected == 5 && run.state != MISSED || selected == 6 && run.state != EXECUTION_UNKNOWN) continue;
                    visibleRuns++;
                    runCard(run);
                }
                if (!state.snapshot().runs().nextCursor.isEmpty()) content.addView(button("加载更早记录", () -> model.more(false)), ui.top(12));
                if (visibleRuns == 0) emptyState(state.snapshot().runs().items.isEmpty() ? "暂无运行记录" : "没有符合条件的记录",
                        state.snapshot().runs().items.isEmpty() ? "计划到期后，这里会显示执行和交付结果。" : "换一个筛选条件，查看其他记录。", null);
            }
            case TEMPLATES -> {
                pageHeader("模板", "从现成的工作流开始", "选定模板，再设置时间和所需授权。", true);
                section("可用模板", state.snapshot().templates().size() + " 项");
                for (ScheduleTemplateInfo template : state.snapshot().templates()) {
                    LinearLayout card = card(); card.addView(ui.eyebrow("WORKFLOW  ·  V" + template.version));
                    card.addView(ui.title(template.title, 21), ui.top(8));
                    card.addView(ui.label(template.description, 14, ui.muted, false), ui.top(7));
                    ui.divider(card, 16);
                    for (ScheduleStepInfo step : template.steps) card.addView(ui.label((step.required ? "必需  ·  " : "可选  ·  ") + step.title + (step.dependencies.isEmpty() ? "" : "  ← " + dependencyText(step, template.steps)), 13, ui.ink, false), ui.top(10));
                    card.addView(button("使用此模板  →", () -> ScheduleEditorDialog.create(null, template).show(getParentFragmentManager(), "schedule-editor")), ui.top(16));
                    content.addView(card, ui.top(10));
                }
                if (state.snapshot().templates().isEmpty()) emptyState("暂无可用模板", "Host 发布工作流模板后，会在这里显示。", null);
            }
            default -> { }
        }
    }
    private void readiness(ScheduleReadiness ready) {
        if (ready.exactAlarmAllowed && ready.notificationsAllowed && ready.userReady && ready.code == 0) return;
        LinearLayout panel = card();
        panel.addView(ui.eyebrow("需要处理"));
        panel.addView(ui.label(ready.reason.isEmpty() ? "系统权限需要检查" : ready.reason, 14, ui.ink, false), ui.top(7));
        if (!ready.exactAlarmAllowed && android.os.Build.VERSION.SDK_INT >= 31)
            panel.addView(button("允许精确闹钟", () -> startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:com.matrix.agent")))), ui.top(12));
        if (!ready.notificationsAllowed) panel.addView(button("检查提醒通知权限与渠道", () -> startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, "com.matrix.agent"))), ui.top(8));
        content.addView(panel, ui.top(16));
    }
    private void renderPlan(ScheduleInfo plan) {
        content.addView(quietAction("‹  返回计划", () -> model.select(ScheduleViewModel.Tab.PLANS)));
        content.addView(ui.eyebrow("计划详情  ·  " + ScheduleLabels.plan(plan)), ui.top(14));
        content.addView(ui.title(plan.spec.title, 27), ui.top(7));
        content.addView(ui.label(plan.spec.action.text, 15, ui.muted, false), ui.top(8));
        LinearLayout facts = card();
        facts.addView(ui.eyebrow("执行安排"));
        facts.addView(text("下次：" + ScheduleLabels.time(plan.nextDueAt)
                + "\n时区：" + plan.spec.timing.zoneId + (plan.spec.timing.followDeviceZone ? "（跟随设备）" : "（固定）")
                + "\n允许迟到：" + plan.spec.graceMillis / 60_000 + " 分钟", 14), ui.top(9));
        ui.divider(facts, 14);
        facts.addView(text("动作：" + (plan.spec.action.kind == WORKFLOW ? "模板 " + plan.spec.action.templateId + " · v" + plan.spec.action.templateVersion : plan.spec.action.kind == AGENT ? "Agent 目标" : plan.spec.action.kind == TOOL ? "确定动作" : "本地通知")
                + "\n授权能力：" + (plan.spec.action.capabilities.isEmpty() ? "无" : String.join("、", plan.spec.action.capabilities))
                + "\n网络：" + (plan.spec.action.allowNetwork ? "允许" : "不允许") + " · 播报：" + (plan.spec.action.speakResult ? "遵守系统策略" : "关闭"), 14), ui.top(12));
        content.addView(facts, ui.top(22));
        section("管理计划", "");
        content.addView(button("预览未来时间", () -> model.previewPlan(plan, preview -> {
            if (isAdded()) new AlertDialog.Builder(requireContext()).setTitle("计划时间预览")
                    .setMessage(preview.code == 0 && !preview.nextOccurrences.isEmpty() ? String.join("\n", preview.nextOccurrences) : preview.message).setPositiveButton("关闭", null).show();
        })), space());
        content.addView(primaryAction("编辑计划", () -> ScheduleEditorDialog.create(plan, null).show(getParentFragmentManager(), "schedule-editor")), space());
        if (plan.state == ACTIVE) {
            content.addView(button("暂停未来触发", () -> model.control(plan, "", PAUSE)), space());
            content.addView(button("跳过下一次", () -> confirm("跳过 " + ScheduleLabels.time(plan.nextDueAt) + " 的发生？", () -> model.control(plan, "", SKIP_NEXT))), space());
            content.addView(dangerAction("暂停并停止当前运行", () -> confirm("暂停计划并请求停止所有未结束运行？已产生的效果不能撤销。", () -> model.control(plan, "", PAUSE_AND_CANCEL))), space());
        } else if (plan.state == PAUSED || plan.state == DRAFT) content.addView(button(plan.state == DRAFT ? "启用草稿计划" : "恢复未来触发", () -> model.control(plan, "", RESUME)), space());
        content.addView(dangerAction("删除计划", () -> confirm("删除计划并停止后续触发？运行历史仍保留。", () -> model.control(plan, "", DELETE))), space());
        section("运行历史", "");
        var history = model.planDetail().runs();
        for (ScheduleRunInfo run : history.items) runCard(run);
        if (history.items.isEmpty()) content.addView(text("此计划暂无运行记录。", 14), space());
        if (!history.nextCursor.isEmpty()) content.addView(button("加载更早记录", model::morePlanRuns), space());
    }
    private void runCard(ScheduleRunInfo run) {
        LinearLayout card = card();
        card.addView(ui.eyebrow(ScheduleLabels.run(run.state)));
        card.addView(ui.title(run.title, 19), ui.top(7));
        card.addView(ui.label(ScheduleLabels.time(run.scheduledAt) + "     ›", 13, ui.muted, false), ui.top(8));
        card.setOnClickListener(v -> model.showRun(run.runId)); content.addView(card, ui.top(10));
    }
    private void renderRun(com.matrix.agent.launcher.data.ScheduleRepository.RunDetail detail) {
        ScheduleRunInfo run = detail.run();
        content.addView(quietAction("‹  返回", model::back));
        content.addView(ui.eyebrow("运行详情  ·  " + ScheduleLabels.run(run.state)), ui.top(14));
        content.addView(ui.title(run.title, 27), ui.top(7));
        LinearLayout timeline = card();
        timeline.addView(ui.eyebrow("执行时间线"));
        if (!run.templateId.isEmpty()) content.addView(text("本次冻结模板：" + run.templateId + " · v" + run.templateVersion, 14), space());
        timeline.addView(text("计划时刻 " + ScheduleLabels.time(run.scheduledAt) + "\n广播接收 " + ScheduleLabels.time(run.receivedAt)
                + "\n受理 " + ScheduleLabels.time(run.admittedAt) + "\n开始 " + ScheduleLabels.time(run.startedAt)
                + "\n结束 " + ScheduleLabels.time(run.completedAt) + "\n投递 " + ScheduleLabels.time(run.deliveredAt)
                + "\n" + ScheduleLabels.delivery(run.deliveryStatus), 14), ui.top(8));
        content.addView(timeline, ui.top(22));
        renderDelivery(run);
        if (!run.reason.isEmpty()) content.addView(text(ScheduleLabels.reason(run.reason), 14), space());
        if (!run.result.isEmpty()) content.addView(text(run.result, 16), space());
        for (ScheduleStepInfo step : detail.steps()) {
            LinearLayout card = card(); card.addView(ui.title(step.title, 18));
            card.addView(ui.eyebrow(ScheduleLabels.run(step.state)), ui.top(6));
            card.addView(text((step.required ? "必需" : "可选") + " · 尝试 " + step.attempt
                    + (step.dependencies.isEmpty() ? "" : "\n前置：" + dependencyText(step, detail.steps()))
                    + "\n开始：" + ScheduleLabels.time(step.startedAt) + " · 结束：" + ScheduleLabels.time(step.completedAt)
                    + (step.completedAt > 0 && step.startedAt > 0 ? " · 耗时 " + Math.max(0, step.completedAt - step.startedAt) + " ms" : "")
                    + "\n" + step.inputSummary + "\n累计活跃耗时：" + step.activeMillis + " ms"
                    + "\n" + step.result + (step.reason.isEmpty() ? "" : "\n" + ScheduleLabels.reason(step.reason)), 14), ui.top(10)); content.addView(card, ui.top(10));
        }
        if (!terminalRun(run.state) || run.deliveryStatus == DELIVERY_PENDING) content.addView(button("停止这次运行", () -> confirm("请求停止这次运行？未来计划仍按原规则触发。", () -> model.control(detail.plan(), run.runId, CANCEL_RUN))), space());
        if (run.state == EXECUTION_UNKNOWN) content.addView(text("结果尚未确认，请核对外部效果。这里不会自动重做可能已完成的操作。", 14), space());
    }
    private void renderDelivery(ScheduleRunInfo run) {
        try {
            var facts = new org.json.JSONObject(run.deliveryFactsJson);
            for (String channel : java.util.List.of("notification", "speech")) {
                var receipt = facts.optJSONObject(channel); if (receipt == null) continue;
                String status = receipt.optString("status", "UNKNOWN");
                String label = channel.equals("notification") ? "通知" : "播报";
                String description = status.equals("DELIVERED") ? channel.equals("notification") ? "已发布" : "已完成" : ScheduleLabels.reason(status);
                String timing = receipt.has("deliveredAt") ? " · " + ScheduleLabels.time(receipt.optLong("deliveredAt")) : " · 未完成交付";
                var policy = receipt.optJSONObject("policy");
                String sound = channel.equals("notification") ? "\n声音未核验" : "";
                if (policy != null && policy.has("interruptionFilter")) sound += " · 勿扰快照：" + switch (policy.optInt("interruptionFilter")) {
                    case 1 -> "关闭"; case 2 -> "优先通知"; case 3 -> "完全静音"; case 4 -> "仅闹钟"; default -> "未知";
                };
                content.addView(text(label + "：" + description + timing + sound, 14), space());
            }
        } catch (org.json.JSONException invalid) { content.addView(text("交付明细不可用，请以总状态为准。", 14), space()); }
    }
    private void filter(String[] names, ScheduleViewModel.Tab tab) {
        LinearLayout frame = ui.row();
        frame.setPadding(dp(14), 0, dp(8), 0);
        frame.setBackground(ui.card().getBackground());
        frame.addView(ui.label("筛选", 13, ui.muted, true));
        Spinner filter = new Spinner(requireContext());
        filter.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, names));
        filter.setSelection(model.filter(tab));
        filter.setMinimumHeight(dp(48));
        filter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { model.filter(tab, position); }
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        frame.addView(filter, new LinearLayout.LayoutParams(0, -2, 1));
        content.addView(frame, ui.top(10));
    }
    private void pageHeader(String eyebrow, String title, String subtitle, boolean refresh) {
        LinearLayout row = ui.row();
        LinearLayout labels = column();
        labels.addView(ui.eyebrow(eyebrow));
        labels.addView(ui.title(title, 25), ui.top(5));
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        if (refresh) row.addView(quietAction("刷新  ↻", model::refresh));
        content.addView(row);
        content.addView(ui.label(subtitle, 14, ui.muted, false), ui.top(8));
    }
    private void section(String title, String count) {
        LinearLayout row = ui.row();
        row.addView(ui.title(title, 19), new LinearLayout.LayoutParams(0, -2, 1));
        if (!count.isEmpty()) row.addView(ui.label(count, 12, ui.muted, false));
        content.addView(row, ui.top(29));
    }
    private void emptyState(String title, String description, Runnable action) {
        LinearLayout panel = card();
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        panel.setPadding(dp(22), dp(32), dp(22), dp(30));
        TextView mark = ui.label("◷", 30, ui.accent, false);
        mark.setGravity(Gravity.CENTER);
        panel.addView(mark);
        TextView heading = ui.title(title, 22); heading.setGravity(Gravity.CENTER);
        panel.addView(heading, ui.top(12));
        TextView copy = ui.label(description, 14, ui.muted, false); copy.setGravity(Gravity.CENTER);
        panel.addView(copy, ui.top(8));
        if (action != null) panel.addView(primaryAction("新建计划", action), ui.top(18));
        content.addView(panel, ui.top(17));
    }
    private static String dependencyText(ScheduleStepInfo step, java.util.List<ScheduleStepInfo> steps) {
        return String.join("、", step.dependencies.stream().map(id -> steps.stream().filter(candidate -> candidate.stepId.equals(id))
                .map(candidate -> candidate.title).findFirst().orElse("已归档步骤")).collect(java.util.stream.Collectors.toList()));
    }
    private void confirm(String message, Runnable action) { new AlertDialog.Builder(requireContext()).setMessage(message).setNegativeButton("返回", null).setPositiveButton("确认", (d, w) -> action.run()).show(); }
    private LinearLayout column() { return ui.column(); }
    private LinearLayout card() { return ui.card(); }
    private TextView text(String value, int size) { return ui.label(value, size, ui.ink, false); }
    private TextView button(String title, Runnable action) { return ui.action(title, false, model.connected() && !model.busy(), action); }
    private TextView dangerAction(String title, Runnable action) { return ui.dangerAction(title, model.connected() && !model.busy(), action); }
    private TextView primaryAction(String title, Runnable action) { return ui.action(title, true, model.connected() && !model.busy(), action); }
    private TextView quietAction(String title, Runnable action) { return ui.quietAction(title, true, action); }
    private LinearLayout.LayoutParams space() { var params = new LinearLayout.LayoutParams(-1, -2); params.topMargin = dp(10); return params; }
    private int dp(int value) { return activity().dp(value); }
    private LauncherActivity activity() { return (LauncherActivity) requireActivity(); }
    @Override public void onSaveInstanceState(@NonNull Bundle saved) {
        if (scroll != null && !renderedKey.isEmpty()) model.scrollPosition(renderedKey, scroll.getScrollY());
        saved.putBundle("scheduleUi", model.saveUiState()); super.onSaveInstanceState(saved);
    }
    @Override public void onStart() { super.onStart(); model.visible(true); }
    @Override public void onStop() { model.visible(false); super.onStop(); }
    @Override public void onDestroyView() {
        if (scroll != null && !renderedKey.isEmpty()) model.scrollPosition(renderedKey, scroll.getScrollY());
        renderedKey = ""; tabBar = null; content = null; scroll = null; instant = null; notice = null; ui = null;
        super.onDestroyView();
    }
}
