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
import com.matrix.agent.launcher.presentation.theme.LauncherThemePreferences;

/** Task center: persistent plans and runs, with the existing instant-task surface kept intact. */
public final class ScheduleFragment extends Fragment {
    private ScheduleViewModel model;
    private LinearLayout content;
    private TextView notice;
    private FrameLayout instant;
    private ScrollView scroll;
    private String renderedKey = "";
    private final java.util.EnumMap<ScheduleViewModel.Tab, Button> tabs = new java.util.EnumMap<>(ScheduleViewModel.Tab.class);
    public static ScheduleFragment forHistory() { var fragment = new ScheduleFragment(); Bundle args = new Bundle(); args.putBoolean("history", true); fragment.setArguments(args); return fragment; }
    public static ScheduleFragment forRun(String id) { var fragment = new ScheduleFragment(); Bundle args = new Bundle(); args.putString("run", id); fragment.setArguments(args); return fragment; }
    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup parent, @Nullable Bundle state) {
        model = new ViewModelProvider(requireActivity(), activity().viewModelFactory()).get(ScheduleViewModel.class);
        if (state != null) model.restoreUiState(state.getBundle("scheduleUi"));
        LinearLayout root = column(); root.setPadding(dp(16), dp(14), dp(16), 0);
        LinearLayout navigation = new LinearLayout(requireContext());
        String[] names = {"计划", "运行记录", "模板", "临时任务"}; int index = 0;
        for (ScheduleViewModel.Tab tab : ScheduleViewModel.Tab.values()) {
            Button button = button(names[index++], () -> model.select(tab)); button.setEnabled(true); tabs.put(tab, button);
            navigation.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
        }
        root.addView(navigation);
        notice = text("", 13); root.addView(notice, space());
        scroll = new ScrollView(requireContext()); content = column(); content.setPadding(0, dp(8), 0, dp(32)); scroll.addView(content);
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
        notice.setText(!state.error().isEmpty() ? state.error() : state.loading() ? "正在同步…" : model.connected() ? "计划由 Host 保存，关闭页面后仍会触发" : "Host 未连接，计划状态暂不可用");
        for (var entry : tabs.entrySet()) entry.getValue().setAlpha(entry.getKey() == state.tab() ? 1f : .55f);
        tabs.get(ScheduleViewModel.Tab.TEMPLATES).setVisibility(model.supports(com.matrix.agent.api.common.MatrixServiceConstants.FEATURE_SCHEDULE_WORKFLOW) ? View.VISIBLE : View.GONE);
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
        content.addView(button("刷新", model::refresh), space());
        if (state.snapshot() == null) { content.addView(text("连接后可查看计划与历史记录。", 16), space()); return; }
        if (state.detail() != null) { renderRun(state.detail()); return; }
        switch (state.tab()) {
            case PLANS -> {
                readiness(state.snapshot().readiness());
                if (!state.selectedPlan().isEmpty()) {
                    var detail = model.planDetail();
                    if (detail != null && detail.plan().scheduleId.equals(state.selectedPlan())) renderPlan(detail.plan(), state);
                    else content.addView(text(state.error().isEmpty() ? "正在读取计划及运行历史…" : "无法读取此计划，请查看上方原因并重试。", 16), space());
                    return;
                }
                content.addView(button("新建计划", () -> ScheduleEditorDialog.create(null, null).show(getParentFragmentManager(), "schedule-editor")), space());
                if (model.supports(com.matrix.agent.api.common.MatrixServiceConstants.FEATURE_CALENDAR_DOMAIN)) content.addView(button("日历与日程绑定", () -> new ScheduleCalendarDialog().show(getParentFragmentManager(), "schedule-calendar")), space());
                filter(new String[]{"全部计划", "启用", "暂停", "需要处理"}, state.tab());
                var plans = new java.util.ArrayList<>(state.snapshot().plans().items);
                plans.sort(java.util.Comparator.comparingLong(plan -> plan.nextDueAt <= 0 ? Long.MAX_VALUE : plan.nextDueAt));
                for (ScheduleInfo plan : plans) {
                    int selected = model.filter(state.tab());
                    if (selected == 1 && plan.state != ACTIVE || selected == 2 && plan.state != PAUSED || selected == 3 && plan.health != BLOCKED) continue;
                    LinearLayout card = card(); card.addView(text(plan.spec.title, 20));
                    TextView goal = text(plan.spec.action.text, 14); goal.setMaxLines(2);
                    goal.setEllipsize(android.text.TextUtils.TruncateAt.END); card.addView(goal, space());
                    state.snapshot().runs().items.stream().filter(run -> run.scheduleId.equals(plan.scheduleId)).findFirst()
                            .ifPresent(run -> card.addView(text("最近运行：" + ScheduleLabels.run(run.state), 13), space()));
                    card.addView(text(ScheduleLabels.plan(plan) + " · 下次 " + ScheduleLabels.time(plan.nextDueAt), 14), space());
                    if (!plan.reason.isEmpty()) card.addView(text("需要处理：" + ScheduleLabels.reason(plan.reason), 13), space());
                    card.setOnClickListener(v -> model.showPlan(plan.scheduleId)); content.addView(card, space());
                }
                if (!state.snapshot().plans().nextCursor.isEmpty()) content.addView(button("加载更多计划", () -> model.more(true)), space());
                if (state.snapshot().plans().items.isEmpty()) content.addView(text("还没有计划。创建提醒或定时执行目标，先预览再启用。", 16), space());
            }
            case RUNS -> {
                filter(new String[]{"全部记录", "未结束", "成功", "部分完成", "失败", "错过", "待核验"}, state.tab());
                for (ScheduleRunInfo run : state.snapshot().runs().items) {
                    int selected = model.filter(state.tab());
                    if (selected == 1 && terminalRun(run.state) || selected == 2 && run.state != SUCCEEDED
                            || selected == 3 && run.state != PARTIAL || selected == 4 && run.state != FAILED
                            || selected == 5 && run.state != MISSED || selected == 6 && run.state != EXECUTION_UNKNOWN) continue;
                    runCard(run);
                }
                if (!state.snapshot().runs().nextCursor.isEmpty()) content.addView(button("加载更早记录", () -> model.more(false)), space());
                if (state.snapshot().runs().items.isEmpty()) content.addView(text("暂无运行记录。到期后将在这里显示受理与执行结果。", 16), space());
            }
            case TEMPLATES -> {
                for (ScheduleTemplateInfo template : state.snapshot().templates()) {
                    LinearLayout card = card(); card.addView(text(template.title + " · v" + template.version, 20)); card.addView(text(template.description, 14), space());
                    for (ScheduleStepInfo step : template.steps) card.addView(text((step.required ? "必需 · " : "可选 · ") + step.title + (step.dependencies.isEmpty() ? "（可并行开始）" : " ← " + dependencyText(step, template.steps)), 14), space());
                    card.addView(button("使用此模板", () -> ScheduleEditorDialog.create(null, template).show(getParentFragmentManager(), "schedule-editor")), space());
                    content.addView(card, space());
                }
                if (state.snapshot().templates().isEmpty()) content.addView(text("当前 Host 暂无可用模板。", 16), space());
            }
            default -> { }
        }
    }
    private void readiness(ScheduleReadiness ready) {
        if (!ready.exactAlarmAllowed) content.addView(button("允许精确闹钟", () -> startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:com.matrix.agent")))), space());
        if (!ready.notificationsAllowed) content.addView(button("检查提醒通知权限与渠道", () -> startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, "com.matrix.agent"))), space());
        if (!ready.userReady || ready.code != 0) content.addView(text("计划准入未就绪：" + ready.reason, 14), space());
    }
    private void renderPlan(ScheduleInfo plan, ScheduleViewModel.State state) {
        content.addView(button("返回计划", () -> model.select(ScheduleViewModel.Tab.PLANS)), space());
        content.addView(text(plan.spec.title, 25), space()); content.addView(text(plan.spec.action.text, 16), space());
        content.addView(text(ScheduleLabels.plan(plan) + "\n下次：" + ScheduleLabels.time(plan.nextDueAt)
                + "\n时区：" + plan.spec.timing.zoneId + (plan.spec.timing.followDeviceZone ? "（跟随设备）" : "（固定）")
                + "\n允许迟到：" + plan.spec.graceMillis / 60_000 + " 分钟", 14), space());
        content.addView(text("动作：" + (plan.spec.action.kind == WORKFLOW ? "模板 " + plan.spec.action.templateId + " · v" + plan.spec.action.templateVersion : plan.spec.action.kind == AGENT ? "Agent 目标" : plan.spec.action.kind == TOOL ? "确定动作" : "本地通知")
                + "\n授权能力：" + (plan.spec.action.capabilities.isEmpty() ? "无" : String.join("、", plan.spec.action.capabilities))
                + "\n网络：" + (plan.spec.action.allowNetwork ? "允许" : "不允许") + " · 播报：" + (plan.spec.action.speakResult ? "遵守系统策略" : "关闭"), 14), space());
        content.addView(button("预览未来时间", () -> model.previewPlan(plan, preview -> {
            if (isAdded()) new AlertDialog.Builder(requireContext()).setTitle("计划时间预览")
                    .setMessage(preview.code == 0 && !preview.nextOccurrences.isEmpty() ? String.join("\n", preview.nextOccurrences) : preview.message).setPositiveButton("关闭", null).show();
        })), space());
        content.addView(button("编辑计划", () -> ScheduleEditorDialog.create(plan, null).show(getParentFragmentManager(), "schedule-editor")), space());
        if (plan.state == ACTIVE) {
            content.addView(button("暂停未来触发", () -> model.control(plan, "", PAUSE)), space());
            content.addView(button("跳过下一次", () -> confirm("跳过 " + ScheduleLabels.time(plan.nextDueAt) + " 的发生？", () -> model.control(plan, "", SKIP_NEXT))), space());
            content.addView(button("暂停并停止当前运行", () -> confirm("暂停计划并请求停止所有未结束运行？已产生的效果不能撤销。", () -> model.control(plan, "", PAUSE_AND_CANCEL))), space());
        } else if (plan.state == PAUSED || plan.state == DRAFT) content.addView(button(plan.state == DRAFT ? "启用草稿计划" : "恢复未来触发", () -> model.control(plan, "", RESUME)), space());
        content.addView(button("删除计划", () -> confirm("删除计划并停止后续触发？运行历史仍保留。", () -> model.control(plan, "", DELETE))), space());
        content.addView(text("此计划的运行历史", 19), space());
        var history = model.planDetail().runs();
        for (ScheduleRunInfo run : history.items) runCard(run);
        if (history.items.isEmpty()) content.addView(text("此计划暂无运行记录。", 14), space());
        if (!history.nextCursor.isEmpty()) content.addView(button("加载更早记录", model::morePlanRuns), space());
    }
    private void runCard(ScheduleRunInfo run) {
        LinearLayout card = card(); card.addView(text(run.title, 19));
        card.addView(text(ScheduleLabels.run(run.state) + " · " + ScheduleLabels.time(run.scheduledAt), 14), space());
        card.setOnClickListener(v -> model.showRun(run.runId)); content.addView(card, space());
    }
    private void renderRun(com.matrix.agent.launcher.data.ScheduleRepository.RunDetail detail) {
        ScheduleRunInfo run = detail.run();
        content.addView(button("返回", model::back), space());
        content.addView(text(run.title, 25), space()); content.addView(text(ScheduleLabels.run(run.state), 19), space());
        if (!run.templateId.isEmpty()) content.addView(text("本次冻结模板：" + run.templateId + " · v" + run.templateVersion, 14), space());
        content.addView(text("计划时刻 " + ScheduleLabels.time(run.scheduledAt) + "\n广播接收 " + ScheduleLabels.time(run.receivedAt)
                + "\n受理 " + ScheduleLabels.time(run.admittedAt) + "\n开始 " + ScheduleLabels.time(run.startedAt)
                + "\n结束 " + ScheduleLabels.time(run.completedAt) + "\n投递 " + ScheduleLabels.time(run.deliveredAt)
                + "\n" + ScheduleLabels.delivery(run.deliveryStatus), 14), space());
        renderDelivery(run);
        if (!run.reason.isEmpty()) content.addView(text(ScheduleLabels.reason(run.reason), 14), space());
        if (!run.result.isEmpty()) content.addView(text(run.result, 16), space());
        for (ScheduleStepInfo step : detail.steps()) {
            LinearLayout card = card(); card.addView(text(step.title + " · " + ScheduleLabels.run(step.state), 17));
            card.addView(text((step.required ? "必需" : "可选") + " · 尝试 " + step.attempt
                    + (step.dependencies.isEmpty() ? "" : "\n前置：" + dependencyText(step, detail.steps()))
                    + "\n开始：" + ScheduleLabels.time(step.startedAt) + " · 结束：" + ScheduleLabels.time(step.completedAt)
                    + (step.completedAt > 0 && step.startedAt > 0 ? " · 耗时 " + Math.max(0, step.completedAt - step.startedAt) + " ms" : "")
                    + "\n" + step.inputSummary + "\n累计活跃耗时：" + step.activeMillis + " ms"
                    + "\n" + step.result + (step.reason.isEmpty() ? "" : "\n" + ScheduleLabels.reason(step.reason)), 14), space()); content.addView(card, space());
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
        Spinner filter = new Spinner(requireContext());
        filter.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, names));
        filter.setSelection(model.filter(tab));
        filter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { model.filter(tab, position); }
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        content.addView(filter, space());
    }
    private static String dependencyText(ScheduleStepInfo step, java.util.List<ScheduleStepInfo> steps) {
        return String.join("、", step.dependencies.stream().map(id -> steps.stream().filter(candidate -> candidate.stepId.equals(id))
                .map(candidate -> candidate.title).findFirst().orElse("已归档步骤")).collect(java.util.stream.Collectors.toList()));
    }
    private void confirm(String message, Runnable action) { new AlertDialog.Builder(requireContext()).setMessage(message).setNegativeButton("返回", null).setPositiveButton("确认", (d, w) -> action.run()).show(); }
    private LinearLayout column() { LinearLayout view = new LinearLayout(requireContext()); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout card() { LinearLayout card = column(); card.setBackgroundResource(R.drawable.bg_card); card.setPadding(dp(16), dp(16), dp(16), dp(16)); card.setMinimumHeight(dp(76)); return card; }
    private TextView text(String text, int size) { TextView view = new TextView(requireContext()); view.setText(text); view.setTextSize(size); view.setTextColor(LauncherThemePreferences.colorResource(requireContext(), R.color.matrix_text)); view.setLineSpacing(dp(3), 1f); return view; }
    private Button button(String title, Runnable action) { Button button = new Button(requireContext()); button.setText(title); button.setTextSize(13); button.setAllCaps(false); button.setBackgroundResource(R.drawable.bg_outline); button.setTextColor(LauncherThemePreferences.colorResource(requireContext(), R.color.matrix_primary_dark)); button.setEnabled(model.connected() && !model.busy()); button.setOnClickListener(v -> action.run()); return button; }
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
        renderedKey = ""; tabs.clear(); content = null; scroll = null; instant = null; notice = null;
        super.onDestroyView();
    }
}
