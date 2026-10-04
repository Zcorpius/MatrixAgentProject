package com.matrix.agent.launcher.presentation;

import static com.matrix.agent.api.schedule.ScheduleCodes.*;

import android.app.Dialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;
import com.matrix.agent.api.schedule.*;
import com.matrix.agent.launcher.LauncherActivity;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Structured draft with an explicit normalized preview before activation. */
public final class ScheduleEditorDialog extends DialogFragment {
    private ScheduleViewModel model;
    private ScheduleInfo existing;
    private ScheduleTemplateInfo template;
    private EditText title, goal, dateTime, minutes, localTime, zone, grace, offset;
    private String calendarBinding = "";
    private Spinner timing, action, misfire;
    private boolean saving;
    private CheckBox followZone, network, speak, calendar, tomorrow;
    private EditText calendarId, startDate, endDate, researchQuery;
    private boolean research;
    private final List<CheckBox> days = new ArrayList<>();
    private TextView error;
    private Bundle restored;
    private String operationId;
    private ScheduleSpec pendingSpec;
    private String draftKey;
    private boolean discardDraft, requestPending;
    private ScheduleUi ui;

    public static ScheduleEditorDialog create(ScheduleInfo plan, ScheduleTemplateInfo template) {
        var dialog = new ScheduleEditorDialog(); Bundle args = new Bundle();
        args.putParcelable("plan", plan); args.putParcelable("template", template); dialog.setArguments(args); return dialog;
    }
    public static ScheduleEditorDialog forCalendar(String binding, String title) {
        var dialog = create(null, null); dialog.requireArguments().putString("binding", binding); dialog.requireArguments().putString("calendarTitle", title); return dialog;
    }
    @NonNull @Override public Dialog onCreateDialog(@Nullable Bundle saved) {
        restored = saved; existing = getArguments() == null ? null : getArguments().getParcelable("plan");
        template = getArguments() == null ? null : getArguments().getParcelable("template");
        model = new ViewModelProvider(requireActivity(), ((LauncherActivity) requireActivity()).viewModelFactory()).get(ScheduleViewModel.class);
        ui = new ScheduleUi(requireContext());
        draftKey = existing != null ? "edit:" + existing.scheduleId : template != null ? "template:" + template.templateId
                : "create:" + requireArguments().getString("binding", "");
        if (saved == null) saved = model.draft(draftKey);
        restored = saved; days.clear();
        operationId = saved == null ? UUID.randomUUID().toString() : saved.getString("operation", UUID.randomUUID().toString());
        pendingSpec = saved == null ? null : saved.getParcelable("pending");
        requestPending = saved != null && saved.getBoolean("requestPending");
        ScheduleSpec initial = existing == null ? null : existing.spec;
        research = "source_research".equals(template != null ? template.templateId : initial == null ? "" : initial.action.templateId);
        calendarBinding = initial == null ? requireArguments().getString("binding", "") : initial.timing.calendarBindingId;
        ScrollView scroll = new ScrollView(requireContext());
        scroll.setBackgroundColor(ui.paper);
        LinearLayout form = ui.column();
        form.setPadding(dp(20), dp(18), dp(20), dp(26)); scroll.addView(form);
        form.addView(ui.eyebrow("任务中心  /  计划"));
        form.addView(ui.title(existing == null ? "新建计划" : "编辑计划", 25), ui.top(6));
        form.addView(ui.label("填写内容与时间，预览后再确认保存。", 13, ui.muted, false), ui.top(7));
        section(form, "01  内容");
        title = field(form, "标题", "title", initial == null ? template == null ? "" : template.title : initial.title, false);
        goal = field(form, "提醒内容或执行目标", "goal", initial == null ? template == null ? "" : template.description : initial.action.text, false); goal.setMinLines(2);
        section(form, "02  时间");
        timing = choice(form, "何时触发", new String[]{"指定日期与时间", "一段时间后", "每天", "每周", "已绑定日历实例"},
                saved == null ? initial == null ? 1 : Math.min(4, initial.timing.kind - 1) : saved.getInt("timing"));
        if (!calendarBinding.isEmpty()) { timing.setSelection(4); timing.setEnabled(false); }
        offset = field(form, "日历事件前多少分钟触发", "offset", initial == null ? "15" : Long.toString(initial.timing.calendarOffsetMillis / 60_000), true);
        offset.setVisibility(calendarBinding.isEmpty() ? View.GONE : View.VISIBLE);
        String defaultDate = LocalDateTime.now().plusMinutes(5).withSecond(0).withNano(0).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        dateTime = field(form, "日期与时间（yyyy-MM-dd HH:mm）", "dateTime", initial != null && initial.timing.atMillis > 0
                ? Instant.ofEpochMilli(initial.timing.atMillis).atZone(ZoneId.of(initial.timing.zoneId)).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) : defaultDate, false);
        minutes = field(form, "延迟多少分钟", "minutes", initial == null ? "5" : Long.toString(Math.max(1, initial.timing.delayMillis / 60_000)), true);
        localTime = field(form, "每天或每周的时间（HH:mm）", "localTime", initial == null || initial.timing.localTime.isEmpty() ? "08:00" : initial.timing.localTime, false);
        LinearLayout weekdays = ui.column();
        LinearLayout firstDays = ui.row(); LinearLayout remainingDays = ui.row();
        weekdays.addView(firstDays); weekdays.addView(remainingDays);
        String[] names = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        for (int i = 0; i < 7; i++) {
            CheckBox day = new CheckBox(requireContext());
            day.setText(names[i]); day.setTextSize(13); day.setTextColor(ui.ink);
            day.setButtonTintList(android.content.res.ColorStateList.valueOf(ui.accent));
            day.setPadding(0, 0, 0, 0);
            day.setChecked(saved == null ? initial != null && (initial.timing.weekdaysMask & (1 << i)) != 0 : saved.getBoolean("day" + i));
            days.add(day);
            (i < 4 ? firstDays : remainingDays).addView(day, new LinearLayout.LayoutParams(0, dp(48), 1));
        }
        form.addView(weekdays, ui.top(8));
        zone = field(form, "时区", "zone", initial == null ? ZoneId.systemDefault().getId() : initial.timing.zoneId, false);
        startDate = field(form, "周期开始日期（可空，yyyy-MM-dd）", "startDate", initial == null ? "" : initial.timing.startDate, false);
        endDate = field(form, "周期结束日期（可空，含当日）", "endDate", initial == null ? "" : initial.timing.endDate, false);
        followZone = check(form, "周期计划跟随设备时区", "follow", initial != null && initial.timing.followDeviceZone);
        section(form, "03  执行方式");
        action = choice(form, "到期动作", new String[]{"本地通知", "Agent 执行"}, saved == null ? initial != null && initial.action.kind == AGENT ? 1 : 0 : saved.getInt("action"));
        if (!model.supports(com.matrix.agent.api.common.MatrixServiceConstants.FEATURE_SCHEDULE_AGENT) && (initial == null || initial.action.kind == NOTIFICATION)) {
            action.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, new String[]{"本地通知"}));
        }
        if (template != null || initial != null && initial.action.kind == WORKFLOW) { action.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, new String[]{"工作流模板执行"})); action.setEnabled(false); label(form, "模板：" + (template == null ? initial.action.templateId : template.title)); }
        calendar = check(form, "允许读取日历以完成目标", "calendar", initial != null && initial.action.capabilities.contains("calendar.query"));
        network = check(form, "允许使用网络与已配置的在线模型", "network", initial != null && initial.action.allowNetwork);
        speak = check(form, "完成后播报（遵守勿扰与通话策略）", "speak", initial != null && initial.action.speakResult);
        org.json.JSONObject parameters;
        try { parameters = new org.json.JSONObject(initial == null ? "{}" : initial.action.parametersJson); }
        catch (org.json.JSONException invalid) { parameters = new org.json.JSONObject(); }
        researchQuery = field(form, "研究问题（最多 300 字）", "researchQuery", parameters.optString("query"), false);
        if (research) label(form, "仅检索百科片段、论文摘要和书目信息，不读取全文。活动执行最多 30 分钟，可在任务中心停止。请明确勾选网络授权。");
        tomorrow = check(form, "同时查询明日日程（可选步骤）", "tomorrow", parameters.optBoolean("includeTomorrow", true));
        calendarId = field(form, "限定日历编号（留空表示所有可读日历）", "calendarId", parameters.has("calendarId") ? parameters.optString("calendarId") : "", true);
        section(form, "04  错过执行");
        misfire = choice(form, "错过时如何处理", new String[]{"跳过历史时段", "宽限内执行", "合并最近一次"},
                saved == null ? initial == null ? WITHIN_GRACE - 1 : initial.misfirePolicy - 1 : saved.getInt("misfire"));
        if (initial != null && initial.action.kind == TOOL) {
            action.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, new String[]{"已授权的确定动作"}));
            action.setEnabled(false);
        }
        grace = field(form, research ? "触发后到期窗口，含排队和执行（分钟，至少 30）" : "允许迟到的分钟数", "grace", initial == null ? research ? "40" : "10" : Long.toString(initial.graceMillis / 60_000), true);
        label(form, "权限、后台条件或待机限制可能使执行延后。预览后确认启用，关闭页面不会暂停计划。返回会保留草稿；放弃只删除本地草稿。");
        error = label(form, ""); error.setTextColor(ui.danger);
        if (requestPending && pendingSpec != null) {
            Button retry = new Button(requireContext()); retry.setText("核对并重试上次保存请求");
            retry.setOnClickListener(v -> new AlertDialog.Builder(requireContext()).setTitle("上次请求")
                    .setMessage(pendingSpec.title + "\n" + pendingSpec.action.text + "\n将复用原操作编号查询或保存。")
                    .setNegativeButton("返回", null).setPositiveButton("重试", (d, w) -> save()).show());
            form.addView(retry);
        }
        AlertDialog dialog = new AlertDialog.Builder(requireContext()).setView(scroll).setNegativeButton("返回", null)
                .setNeutralButton("放弃", null)
                .setPositiveButton("预览", null).create();
        Runnable updateFields = () -> {
            int selected = timing.getSelectedItemPosition() + 1;
            boolean recurring = selected == DAILY || selected == WEEKLY;
            show(dateTime, selected == ONCE); show(minutes, selected == AFTER_DELAY); show(localTime, recurring);
            show(startDate, recurring); show(endDate, recurring); show(offset, selected == CALENDAR_OFFSET);
            weekdays.setVisibility(selected == WEEKLY ? View.VISIBLE : View.GONE); followZone.setVisibility(recurring ? View.VISIBLE : View.GONE);
            boolean workflow = template != null || initial != null && initial.action.kind == WORKFLOW;
            boolean executes = workflow || action.getSelectedItemPosition() == 1;
            calendar.setVisibility(executes && !workflow ? View.VISIBLE : View.GONE);
            network.setVisibility(executes ? View.VISIBLE : View.GONE); speak.setVisibility(executes ? View.VISIBLE : View.GONE);
            tomorrow.setVisibility(workflow && !research ? View.VISIBLE : View.GONE); show(calendarId, workflow && !research); show(researchQuery, workflow && research);
        };
        AdapterView.OnItemSelectedListener changed = new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { updateFields.run(); }
            public void onNothingSelected(AdapterView<?> parent) { }
        };
        timing.setOnItemSelectedListener(changed); action.setOnItemSelectedListener(changed); updateFields.run();
        dialog.setOnShowListener(ignored -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(ui.dialogBackground());
                dialog.getWindow().setLayout(
                        (int) (getResources().getDisplayMetrics().widthPixels * .94f),
                        (int) (getResources().getDisplayMetrics().heightPixels * .88f));
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(ui.danger);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(ui.muted);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ui.accentDeep);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> preview());
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                if (requestPending) { error.setText("上次保存结果尚未确认，请先预览并重试原请求。"); return; }
                discardDraft = true; model.draft(draftKey, null); dismiss();
            });
        });
        return dialog;
    }
    private void preview() {
        if (saving || !model.connected()) return;
        if (requestPending) {
            error.setText("上次保存结果尚未确认，请先重试原请求，避免重复创建。");
            save(); return;
        }
        try {
            ScheduleSpec draft = draft(); error.setText("正在预览…");
            model.preview(draft, preview -> {
                if (!isAdded()) return;
                if (preview.code != 0) { error.setText(preview.message); return; }
                if (pendingSpec != null) operationId = UUID.randomUUID().toString();
                pendingSpec = preview.spec;
                new AlertDialog.Builder(requireContext()).setTitle("确认计划")
                        .setMessage(preview.spec.title + "\n\n" + preview.spec.action.text + "\n\n接下来：\n" + String.join("\n", preview.nextOccurrences)
                                + (preview.spec.timing.kind == AFTER_DELAY ? "\n相对延迟从确认保存时起算。" : "")
                                + "\n时区：" + preview.spec.timing.zoneId + " · 宽限：" + preview.spec.graceMillis / 60_000 + " 分钟"
                                + "\n通知遵循系统渠道与勿扰设置。")
                        .setNegativeButton("继续编辑", (d, w) -> { operationId = UUID.randomUUID().toString(); pendingSpec = null; })
                        .setPositiveButton(existing == null ? "启用计划" : "保存修改", (d, w) -> save()).show();
            });
        } catch (RuntimeException invalid) { error.setText("请检查日期、时间、时区与数值：" + invalid.getMessage()); }
    }
    private void save() {
        if (pendingSpec == null || saving || !model.connected()) return;
        saving = true; requestPending = true;
        model.save(existing, pendingSpec, operationId, result -> {
            saving = false;
            requestPending = result.code == com.matrix.agent.api.common.MatrixErrorCode.SERVICE_NOT_READY
                    || result.code == com.matrix.agent.api.common.MatrixErrorCode.TIMED_OUT;
            if (result.code == 0) { discardDraft = true; model.draft(draftKey, null); if (isAdded()) { Toast.makeText(requireContext(), result.message, Toast.LENGTH_LONG).show(); dismiss(); } return; }
            if (!isAdded()) return;
            error.setText(result.message);
            if (result.code == 2 || result.code == 4) new AlertDialog.Builder(requireContext())
                    .setMessage("尚未获得保存回执。重试会复用原操作编号，不会创建重复计划。")
                    .setNegativeButton("稍后处理", null).setPositiveButton("重试原请求", (d, w) -> save()).show();
            else operationId = UUID.randomUUID().toString();
        });
    }
    private ScheduleSpec draft() {
        int kind = timing.getSelectedItemPosition() + 1;
        ZoneId selectedZone = ZoneId.of(value(zone)); long at = 0, delay = 0; int mask = 0;
        if (kind == CALENDAR_OFFSET && calendarBinding.isEmpty()) throw new IllegalArgumentException("请先从日历选择一个实例");
        if (kind == ONCE) at = LocalDateTime.parse(value(dateTime), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")).atZone(selectedZone).toInstant().toEpochMilli();
        if (kind == AFTER_DELAY) delay = Math.multiplyExact(Long.parseLong(value(minutes)), 60_000);
        for (int i = 0; i < days.size(); i++) if (days.get(i).isChecked()) mask |= 1 << i;
        ScheduleTiming time = new ScheduleTiming(kind, selectedZone.getId(), at, delay, value(localTime), mask,
                value(startDate), value(endDate),
                followZone.isChecked(), calendarBinding, kind == CALENDAR_OFFSET ? Math.multiplyExact(Long.parseLong(value(offset)), 60_000) : 0);
        boolean workflow = template != null || existing != null && existing.spec.action.kind == WORKFLOW;
        int actionKind = workflow ? WORKFLOW : existing != null && existing.spec.action.kind == TOOL ? TOOL : action.getSelectedItemPosition() == 0 ? NOTIFICATION : AGENT;
        List<String> capabilities = actionKind == NOTIFICATION ? List.of() : actionKind == TOOL ? existing.spec.action.capabilities : workflow
                ? template != null ? template.capabilities : existing.spec.action.capabilities
                : calendar.isChecked() ? List.of("calendar.query") : List.of();
        String encodedParameters = existing == null ? "{}" : existing.spec.action.parametersJson;
        if (workflow) {
            try {
                var parameters = new org.json.JSONObject();
                if (research) parameters.put("query", value(researchQuery));
                else {
                    parameters.put("includeTomorrow", tomorrow.isChecked());
                    if (!value(calendarId).isEmpty()) parameters.put("calendarId", Long.parseLong(value(calendarId)));
                }
                encodedParameters = parameters.toString();
            } catch (org.json.JSONException invalid) { throw new IllegalArgumentException(invalid); }
        }
        ScheduleAction execution = new ScheduleAction(actionKind, value(goal), workflow ? template == null ? existing.spec.action.templateId : template.templateId : "",
                workflow ? template == null ? existing.spec.action.templateVersion : template.version : 0,
                encodedParameters, capabilities,
                actionKind != NOTIFICATION && network.isChecked(), actionKind != NOTIFICATION && speak.isChecked());
        return new ScheduleSpec(value(title), time, execution, Math.multiplyExact(Long.parseLong(value(grace)), 60_000), misfire.getSelectedItemPosition() + 1);
    }
    @Override public void onSaveInstanceState(@NonNull Bundle saved) {
        super.onSaveInstanceState(saved); writeDraft(saved);
    }
    private void writeDraft(Bundle saved) {
        saved.putBoolean("requestPending", requestPending); saved.putString("operation", operationId); saved.putParcelable("pending", pendingSpec);
        EditText[] fields = {title, goal, dateTime, minutes, localTime, zone, grace, offset, calendarId, startDate, endDate, researchQuery}; String[] names = {"title", "goal", "dateTime", "minutes", "localTime", "zone", "grace", "offset", "calendarId", "startDate", "endDate", "researchQuery"};
        for (int i = 0; i < fields.length; i++) saved.putString(names[i], value(fields[i]));
        saved.putInt("misfire", misfire.getSelectedItemPosition()); saved.putInt("timing", timing.getSelectedItemPosition()); saved.putInt("action", action.getSelectedItemPosition());
        saved.putBoolean("tomorrow", tomorrow.isChecked());
        saved.putBoolean("follow", followZone.isChecked()); saved.putBoolean("network", network.isChecked()); saved.putBoolean("speak", speak.isChecked()); saved.putBoolean("calendar", calendar.isChecked());
        for (int i = 0; i < days.size(); i++) saved.putBoolean("day" + i, days.get(i).isChecked());
    }
    @Override public void onDismiss(@NonNull android.content.DialogInterface dialog) {
        if (model != null && title != null && !discardDraft) {
            Bundle saved = new Bundle(); writeDraft(saved); model.draft(draftKey, saved);
        }
        super.onDismiss(dialog);
    }
    private EditText field(LinearLayout form, String caption, String key, String initial, boolean number) {
        LinearLayout group = ui.column(); form.addView(group, ui.top(5));
        label(group, caption);
        EditText input = new EditText(requireContext()); input.setSingleLine(!key.equals("goal"));
        if (number) input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(restored == null ? initial : restored.getString(key, initial));
        input.setTextSize(15); input.setTextColor(ui.ink); input.setHintTextColor(ui.muted);
        input.setBackground(ui.fieldBackground()); input.setMinHeight(dp(50));
        input.setPadding(dp(14), dp(10), dp(14), dp(10));
        group.addView(input, ui.top(5)); return input;
    }
    private Spinner choice(LinearLayout form, String caption, String[] choices, int selected) {
        label(form, caption);
        Spinner spinner = new Spinner(requireContext());
        spinner.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_dropdown_item, choices));
        spinner.setSelection(selected);
        spinner.setBackground(ui.fieldBackground()); spinner.setMinimumHeight(dp(50)); spinner.setPadding(dp(12), 0, dp(9), 0);
        form.addView(spinner, ui.top(5)); return spinner;
    }
    private CheckBox check(LinearLayout form, String title, String key, boolean initial) {
        CheckBox view = new CheckBox(requireContext()); view.setText(title); view.setTextSize(14); view.setTextColor(ui.ink);
        view.setMinHeight(dp(48)); view.setChecked(restored == null ? initial : restored.getBoolean(key, initial));
        form.addView(view, ui.top(4)); return view;
    }
    private TextView label(LinearLayout form, String text) {
        TextView label = ui.label(text, 12, ui.muted, true);
        form.addView(label, ui.top(13)); return label;
    }
    private void section(LinearLayout form, String text) {
        form.addView(ui.eyebrow(text), ui.top(26));
        ui.divider(form, 9);
    }
    private static void show(EditText field, boolean visible) { ((View)field.getParent()).setVisibility(visible ? View.VISIBLE : View.GONE); }
    private static String value(EditText input) { return input == null ? "" : input.getText().toString().trim(); }
    private int dp(int value) { return (int) (getResources().getDisplayMetrics().density * value); }
}
