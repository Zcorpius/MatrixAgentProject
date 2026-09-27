package com.matrix.agent.launcher.presentation;

import android.app.Dialog;
import android.os.Bundle;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.lifecycle.ViewModelProvider;
import com.matrix.agent.launcher.LauncherActivity;
import org.json.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** Explicit selection of a Provider instance, kept separate from authorization of its future action. */
public final class ScheduleCalendarDialog extends DialogFragment {
    private ScheduleViewModel model;
    private LinearLayout content;
    private TextView status;
    @NonNull @Override public Dialog onCreateDialog(@Nullable Bundle state) {
        model = new ViewModelProvider(requireActivity(), ((LauncherActivity)requireActivity()).viewModelFactory()).get(ScheduleViewModel.class);
        ScrollView scroll = new ScrollView(requireContext()); content = new LinearLayout(requireContext()); content.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(16 * getResources().getDisplayMetrics().density); content.setPadding(pad,pad,pad,pad); scroll.addView(content);
        status = new TextView(requireContext()); content.addView(status);
        load();
        return new AlertDialog.Builder(requireContext()).setTitle("日历 · 未来 30 天").setView(scroll).setNegativeButton("关闭", null).create();
    }
    private void load() {
        content.removeAllViews(); content.addView(status); status.setText("正在读取日历…");
        add("新建普通日程", this::chooseCalendar);
        JSONObject range = object("startMillis", System.currentTimeMillis()); put(range, "endMillis", System.currentTimeMillis() + 30L * 86_400_000);
        model.calendar("calendar.query", range.toString(), UUID.randomUUID().toString(), result -> {
            if (!isAdded()) return;
            status.setText(result.code == 0 ? "选择日程可绑定 Agent 提醒或执行目标。普通日程不会自动授权 Agent。" : result.message);
            if (result.code != 0) return;
            try {
                JSONArray rows = new JSONObject(result.payloadJson).getJSONArray("events");
                if (rows.length() == 0) status.append("\n此范围内没有日程。");
                for (int i=0; i<rows.length(); i++) {
                    JSONObject event = rows.getJSONObject(i);
                    add(event.optString("title", "未命名日程") + "\n" + time(event.getLong("startMillis")), () -> chooseEvent(event));
                }
                if (new JSONObject(result.payloadJson).optBoolean("truncated")) status.append("\n仅显示前 100 条日程。");
            } catch (JSONException invalid) { status.setText("日历响应不可解析"); }
        });
    }
    private void chooseEvent(JSONObject event) {
        new AlertDialog.Builder(requireContext()).setTitle(event.optString("title"))
                .setMessage("绑定当前实例后，改期会重排尚未领取的运行。日历描述不会作为执行指令。全天事件请改用指定日期与时刻计划。")
                .setNegativeButton("返回", null).setNeutralButton("仅 Agent 提醒", (d,w) -> bind(event, "AGENT"))
                .setPositiveButton("明确保留双提醒", (d,w) -> bind(event, "BOTH_EXPLICIT")).show();
    }
    private void bind(JSONObject event, String owner) {
        model.bindCalendar(event.optLong("eventId"), event.optLong("originalStartMillis"), owner, UUID.randomUUID().toString(), result -> {
            if (!isAdded()) return;
            status.setText(result.message);
            if (result.code == 0) {
                try {
                    String id = new JSONObject(result.payloadJson).getString("bindingId");
                    ScheduleEditorDialog.forCalendar(id, event.optString("title")).show(getParentFragmentManager(), "schedule-editor");
                    dismiss();
                } catch (JSONException invalid) { status.setText("绑定响应不可解析"); }
            }
        });
    }
    private void chooseCalendar() {
        model.calendar("calendar.list", "{}", UUID.randomUUID().toString(), result -> {
            if (!isAdded()) return;
            if (result.code != 0) { status.setText(result.message); return; }
            try {
                JSONArray rows = new JSONObject(result.payloadJson).getJSONArray("calendars");
                java.util.List<Long> ids = new java.util.ArrayList<>(); java.util.List<String> names = new java.util.ArrayList<>();
                for (int i=0; i<rows.length(); i++) { var row=rows.getJSONObject(i); if (row.getBoolean("writable")) { ids.add(row.getLong("calendarId")); names.add(row.getString("name")); } }
                if (ids.isEmpty()) {
                    new AlertDialog.Builder(requireContext()).setMessage("设备没有可写日历。创建 MatrixAgent 本地日历后即可保存普通日程。")
                            .setNegativeButton("返回",null).setPositiveButton("创建本地日历", (d,w) -> model.calendar("calendar.initialize", "{}", UUID.randomUUID().toString(), created -> {
                                if (!isAdded()) return; status.setText(created.message); if(created.code==0) chooseCalendar();
                            })).show();
                } else new AlertDialog.Builder(requireContext()).setTitle("选择所属日历").setItems(names.toArray(new String[0]), (d,w) -> createEvent(ids.get(w))).show();
            } catch (JSONException invalid) { status.setText("日历响应不可解析"); }
        });
    }
    private void createEvent(long calendar) {
        LinearLayout form = new LinearLayout(requireContext()); form.setOrientation(LinearLayout.VERTICAL);
        EditText title=field(form,"日程标题", ""), begin=field(form,"开始：yyyy-MM-dd HH:mm",LocalDateTime.now().plusHours(1).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))),
                end=field(form,"结束：yyyy-MM-dd HH:mm",LocalDateTime.now().plusHours(2).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        CheckBox reminder = new CheckBox(requireContext()); reminder.setText("使用日历原生提醒（提前 10 分钟）"); form.addView(reminder);
        TextView message = new TextView(requireContext()); form.addView(message);
        String operation = UUID.randomUUID().toString();
        AlertDialog dialog = new AlertDialog.Builder(requireContext()).setTitle("新建普通日程").setView(form).setNegativeButton("返回", null).setPositiveButton("保存日程",null).create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            try {
                var zone=ZoneId.systemDefault(); var format=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
                JSONObject args=object("calendarId",calendar); put(args,"title",title.getText().toString().trim());
                put(args,"startMillis",LocalDateTime.parse(begin.getText(),format).atZone(zone).toInstant().toEpochMilli());
                put(args,"endMillis",LocalDateTime.parse(end.getText(),format).atZone(zone).toInstant().toEpochMilli()); put(args,"timeZone",zone.getId());
                if(reminder.isChecked()) put(args,"reminderMinutes",10);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                model.calendar("calendar.create",args.toString(),operation,result -> {
                    if (!isAdded()) return;
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    if(result.code==0) { dialog.dismiss(); load(); } else message.setText(result.message);
                });
            } catch (RuntimeException invalid) { message.setText("请检查标题与日期时间"); }
        })); dialog.show();
    }
    private EditText field(LinearLayout form,String hint,String value) { var input=new EditText(requireContext()); input.setHint(hint); input.setText(value); form.addView(input); return input; }
    private void add(String label,Runnable click) { var button=new Button(requireContext()); button.setText(label); button.setOnClickListener(v -> click.run()); content.addView(button); }
    private static String time(long value) { return Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm")); }
    private static JSONObject object(String key,Object value) { JSONObject object=new JSONObject(); put(object,key,value); return object; }
    private static void put(JSONObject object,String key,Object value) { try { object.put(key,value); } catch(JSONException invalid) { throw new IllegalArgumentException(invalid); } }
}
