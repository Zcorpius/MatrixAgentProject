package com.matrix.agent.platform.calendar;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.schedule.domain.ScheduleCodec;
import com.matrix.agent.task.capability.CapabilityProvider;
import com.matrix.agent.task.tool.ToolResult;
import java.time.*;
import java.util.*;

/** CalendarContract adapter with bounded reads, explicit series scope and post-write verification. */
public final class CalendarClockProvider implements CapabilityProvider {
    private static final String ACCOUNT = "MatrixAgent";
    private static final String[] EVENT_COLUMNS = {"_id", "calendar_id", "title", "description", "eventLocation",
            "dtstart", "dtend", "eventTimezone", "allDay", "rrule", "duration", "deleted", "eventStatus", "customAppUri", "customAppPackage", "original_id", "originalInstanceTime", "originalAllDay"};
    private final Context context;
    private final ContentResolver resolver;
    public CalendarClockProvider(Context context) { this.context = context.getApplicationContext(); resolver = context.getContentResolver(); }
    private final Object writes = new Object();
    @Override public ToolResult execute(AgentRequest request, ToolCall call) {
        if (call.getCapabilityName().startsWith("clock.") || Set.of("calendar.initialize", "calendar.create", "calendar.update", "calendar.delete").contains(call.getCapabilityName())) {
            synchronized (writes) { return executeCall(request, call); }
        }
        return executeCall(request, call);
    }
    private ToolResult executeCall(AgentRequest request, ToolCall call) {
        long began = android.os.SystemClock.elapsedRealtime(); String capability = call.getCapabilityName();
        try {
            if (request.isCancelled() || !request.getExecutionScope().rejection().isEmpty()) return ToolResult.rejected(capability, "授权已失效");
            if (capability.startsWith("clock.")) return clock(call, began);
            requirePermission(Manifest.permission.READ_CALENDAR);
            boolean write = Set.of("calendar.initialize", "calendar.create", "calendar.update", "calendar.delete").contains(capability);
            if (write) requirePermission(Manifest.permission.WRITE_CALENDAR);
            Map<String, Object> data = switch (capability) {
                case "calendar.list" -> Map.of("calendars", calendars());
                case "calendar.initialize" -> Map.of("calendarId", initialize());
                case "calendar.query" -> query(number(call, "startMillis"), number(call, "endMillis"), optionalNumber(call, "calendarId", -1));
                case "calendar.get" -> Map.of("event", event(number(call, "eventId")));
                case "calendar.instance" -> instance(number(call, "eventId"), number(call, "originalStartMillis"));
                case "calendar.create" -> create(request, call);
                case "calendar.update" -> update(request, call, false);
                case "calendar.delete" -> update(request, call, true);
                default -> throw new IllegalArgumentException("未知日历能力");
            };
            return new ToolResult(ToolResult.Status.SUCCESS, capability, "日历操作已回读核验", data, true, elapsed(began));
        } catch (SecurityException denied) { return ToolResult.rejected(capability, "日历权限未授予或不可写"); }
        catch (IllegalArgumentException invalid) { return ToolResult.rejected(capability, invalid.getMessage()); }
        catch (Exception failure) { return new ToolResult(Set.of("calendar.create", "calendar.update", "calendar.delete", "calendar.initialize").contains(capability)
                ? ToolResult.Status.EXECUTION_UNKNOWN : ToolResult.Status.EXECUTION_FAILED, capability,
                "日历操作未获得完整回执，请查询核对", Map.of(), false, elapsed(began)); }
    }
    private List<Map<String, Object>> calendars() {
        List<Map<String, Object>> result = new ArrayList<>();
        try (Cursor cursor = resolver.query(CalendarContract.Calendars.CONTENT_URI,
                new String[]{"_id", "calendar_displayName", "calendar_access_level", "account_name", "account_type", "maxReminders", "allowedReminders"}, null, null, "_id")) {
            if (cursor == null) throw new IllegalStateException("calendar provider unavailable");
            while (cursor.moveToNext() && result.size() < 100) result.add(Map.of("calendarId", cursor.getLong(0), "name", value(cursor, 1),
                    "writable", cursor.getInt(2) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR,
                    "account", value(cursor, 3), "accountType", value(cursor, 4), "maxReminders", cursor.getInt(5), "allowedReminders", value(cursor, 6)));
        }
        return result;
    }
    private long initialize() {
        for (Map<String, Object> calendar : calendars()) if (ACCOUNT.equals(calendar.get("account")) && "LOCAL".equals(calendar.get("accountType"))) return (Long) calendar.get("calendarId");
        ContentValues values = new ContentValues(); values.put("account_name", ACCOUNT); values.put("account_type", "LOCAL");
        values.put("name", "matrix-agent-local"); values.put("calendar_displayName", "MatrixAgent 本地日历");
        values.put("calendar_color", 0xff397d68); values.put("calendar_access_level", CalendarContract.Calendars.CAL_ACCESS_OWNER);
        values.put("ownerAccount", ACCOUNT); values.put("visible", 1); values.put("sync_events", 1);
        values.put("maxReminders", 5); values.put("allowedReminders", "1");
        Uri uri = CalendarContract.Calendars.CONTENT_URI.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter("account_name", ACCOUNT).appendQueryParameter("account_type", "LOCAL").build();
        Uri created = resolver.insert(uri, values);
        if (created == null) throw new IllegalStateException("calendar creation failed");
        long id = ContentUris.parseId(created); requireWritable(id); return id;
    }
    private void requireWritable(long calendarId) {
        for (Map<String, Object> calendar : calendars()) if (((Long) calendar.get("calendarId")) == calendarId && Boolean.TRUE.equals(calendar.get("writable"))) return;
        throw new SecurityException("calendar is not writable");
    }
    private void requireReminderSupport(long calendar, ToolCall call) {
        if (call.argument("reminderMinutes") == null) return;
        for (var row : calendars()) if (((Number)row.get("calendarId")).longValue() == calendar) {
            boolean alert = Arrays.asList(row.get("allowedReminders").toString().split(",")).contains("1");
            if (((Number)row.get("maxReminders")).intValue() < 1 || !alert) throw new IllegalArgumentException("此日历不支持所选原生提醒方式");
            return;
        }
        throw new IllegalArgumentException("日历不存在");
    }
    private Map<String, Object> query(long start, long end, long calendar) {
        if (start <= 0 || end <= start || end - start > 366L * 86_400_000) throw new IllegalArgumentException("查询范围须在 366 天以内");
        Uri.Builder uri = CalendarContract.Instances.CONTENT_URI.buildUpon(); ContentUris.appendId(uri, start); ContentUris.appendId(uri, end);
        List<Map<String, Object>> events = new ArrayList<>(); boolean truncated = false;
        try (Cursor cursor = resolver.query(uri.build(), new String[]{"event_id", "calendar_id", "title", "begin", "end", "eventLocation", "allDay", "originalInstanceTime", "original_id"},
                calendar < 0 ? null : "calendar_id=?", calendar < 0 ? null : new String[]{Long.toString(calendar)}, "begin ASC, event_id ASC")) {
            if (cursor == null) throw new IllegalStateException("calendar query failed");
            while (cursor.moveToNext()) {
                if (events.size() == 100) { truncated = true; break; }
                long original = cursor.isNull(7) ? cursor.getLong(3) : cursor.getLong(7);
                events.add(Map.of("eventId", cursor.isNull(8) ? cursor.getLong(0) : cursor.getLong(8), "calendarId", cursor.getLong(1), "title", value(cursor, 2),
                        "startMillis", cursor.getLong(3), "endMillis", cursor.getLong(4), "location", value(cursor, 5),
                        "allDay", cursor.getInt(6) == 1, "originalStartMillis", original));
            }
        }
        return Map.of("events", events, "count", events.size(), "truncated", truncated, "rangeStart", start, "rangeEnd", end);
    }
    /** Resolve exceptions by original identity before considering the series expansion. */
    private Map<String, Object> instance(long seriesId, long original) {
        Map<String, Object> source;
        try { source = event(seriesId); }
        catch (IllegalArgumentException missing) { return Map.of("status", "DELETED"); }
        long actualId = seriesId;
        boolean recurring = !source.get("rrule").toString().isEmpty();
        if (recurring) {
            try (Cursor cursor = resolver.query(CalendarContract.Events.CONTENT_URI, new String[]{"_id"},
                    "original_id=? AND originalInstanceTime=? AND deleted=0",
                    new String[]{Long.toString(seriesId), Long.toString(original)}, "_id DESC")) {
                if (cursor == null) throw new IllegalStateException("exception lookup unavailable");
                if (cursor.moveToFirst()) { actualId = cursor.getLong(0); source = event(actualId); }
            }
        }
        if (source.get("eventStatus") instanceof Number status && status.intValue() == CalendarContract.Events.STATUS_CANCELED) {
            return Map.of("status", "DELETED");
        }
        long begin = ((Number) source.get("dtstart")).longValue();
        long end = source.get("dtend") instanceof Number n ? n.longValue() : begin;
        if (recurring && actualId == seriesId) {
            Uri.Builder uri = CalendarContract.Instances.CONTENT_URI.buildUpon();
            ContentUris.appendId(uri, original); ContentUris.appendId(uri, original + 1);
            try (Cursor cursor = resolver.query(uri.build(), new String[]{"begin", "end"}, "event_id=? AND begin=?",
                    new String[]{Long.toString(seriesId), Long.toString(original)}, null)) {
                if (cursor == null) throw new IllegalStateException("instance lookup unavailable");
                if (!cursor.moveToFirst()) return Map.of("status", "DELETED");
                begin = cursor.getLong(0); end = cursor.getLong(1);
            }
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("status", "VALID"); snapshot.put("eventId", seriesId); snapshot.put("resolvedEventId", actualId);
        snapshot.put("calendarId", source.get("calendar_id")); snapshot.put("originalStartMillis", original);
        snapshot.put("startMillis", begin); snapshot.put("endMillis", end); snapshot.put("title", source.get("title"));
        snapshot.put("allDay", ((Number) source.get("allDay")).intValue() == 1);
        snapshot.put("reminders", source.get("reminders")); snapshot.put("sourceRevision", source.get("revision"));
        return snapshot;
    }
    private Map<String, Object> event(long id) {
        Map<String, Object> result = new LinkedHashMap<>();
        try (Cursor cursor = resolver.query(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), EVENT_COLUMNS, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst() || cursor.getInt(11) != 0) throw new IllegalArgumentException("事件不存在");
            for (int i = 0; i < EVENT_COLUMNS.length; i++) result.put(EVENT_COLUMNS[i], cursor.isNull(i) ? "" : cursor.getType(i) == Cursor.FIELD_TYPE_INTEGER ? cursor.getLong(i) : cursor.getString(i));
        }
        List<Integer> reminders = new ArrayList<>();
        try (Cursor cursor = resolver.query(CalendarContract.Reminders.CONTENT_URI, new String[]{"minutes", "method"}, "event_id=?", new String[]{Long.toString(id)}, "minutes")) {
            if (cursor == null) throw new IllegalStateException("reminders unavailable");
            while (cursor.moveToNext()) reminders.add(cursor.getInt(0));
        }
        result.put("reminders", reminders);
        result.put("revision", ScheduleCodec.digest(new org.json.JSONObject(result).toString()));
        return result;
    }
    private Map<String, Object> create(AgentRequest request, ToolCall call) throws Exception {
        long calendar = number(call, "calendarId"); requireWritable(calendar); requireReminderSupport(calendar, call);
        ContentValues values = eventValues(call); values.put("calendar_id", calendar);
        String base = "matrix-event://" + request.getRequestId() + "/" + call.getStepId();
        String key = base + "/" + ScheduleCodec.digest(ScheduleCodec.canonicalObject(new org.json.JSONObject(call.getArguments()).toString()));
        try (Cursor cursor = resolver.query(CalendarContract.Events.CONTENT_URI, new String[]{"_id"}, "customAppUri LIKE ? AND deleted=0", new String[]{base + "/%"}, null)) {
            if (cursor == null) throw new IllegalStateException("idempotency query failed");
            if (cursor.moveToFirst()) {
                var existing = event(cursor.getLong(0));
                if (!key.equals(existing.get("customAppUri"))) throw new IllegalArgumentException("事件请求编号对应不同载荷");
                verify(values, existing); verifyReminder(call, existing);
                return Map.of("eventId", cursor.getLong(0), "event", existing);
            }
        }
        values.put("customAppUri", key); values.put("customAppPackage", context.getPackageName());
        ArrayList<ContentProviderOperation> operations = new ArrayList<>();
        operations.add(ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI).withValues(values).build());
        if (call.argument("reminderMinutes") != null) operations.add(ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI)
                .withValueBackReference("event_id", 0).withValue("minutes", reminder(call)).withValue("method", CalendarContract.Reminders.METHOD_ALERT).build());
        ContentProviderResult[] results = resolver.applyBatch(CalendarContract.AUTHORITY, operations);
        long id = ContentUris.parseId(results[0].uri); Map<String, Object> readback = event(id); verify(values, readback); verifyReminder(call, readback);
        return Map.of("eventId", id, "event", readback);
    }
    private Map<String, Object> update(AgentRequest request, ToolCall call, boolean delete) throws Exception {
        long id = number(call, "eventId"); Map<String, Object> before = event(id);
        requireWritable(((Number) before.get("calendar_id")).longValue());
        if (!delete) requireReminderSupport(((Number)before.get("calendar_id")).longValue(), call);
        String scope = string(call, "scope", 16);
        if (!scope.equals("series") && !scope.equals("instance")) throw new IllegalArgumentException("请明确整个系列或单个实例");
        boolean recurring = !before.get("rrule").toString().isEmpty();
        if (recurring && scope.equals("instance")) {
            long original = number(call, "originalStartMillis");
            var currentInstance = instance(id, original);
            if (!"VALID".equals(currentInstance.get("status")) || !Objects.equals(currentInstance.get("sourceRevision"), call.argument("revision")))
                throw new IllegalArgumentException("实例已变化，请先查询 calendar.instance 并核对版本");
            long resolved = ((Number)currentInstance.get("resolvedEventId")).longValue();
            ContentValues exception = delete ? new ContentValues() : eventValues(call);
            exception.put("calendar_id", ((Number) before.get("calendar_id")).longValue()); exception.put("original_id", id);
            exception.put("originalInstanceTime", original); exception.put("originalAllDay", ((Number) before.get("allDay")).intValue());
            if (delete) exception.put("eventTimezone", before.get("eventTimezone").toString());
            exception.putNull("rrule");
            if (delete) { exception.put("eventStatus", CalendarContract.Events.STATUS_CANCELED); exception.put("dtstart", original); exception.put("dtend", original + 60_000); }
            ArrayList<ContentProviderOperation> operations = new ArrayList<>();
            if (resolved == id) operations.add(ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI).withValues(exception).build());
            else operations.add(ContentProviderOperation.newUpdate(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, resolved)).withValues(exception).withExpectedCount(1).build());
            if (!delete && call.argument("reminderMinutes") != null) {
                if (resolved != id) operations.add(ContentProviderOperation.newDelete(CalendarContract.Reminders.CONTENT_URI).withSelection("event_id=?", new String[]{Long.toString(resolved)}).build());
                var reminder = ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI)
                        .withValue("minutes", reminder(call)).withValue("method", CalendarContract.Reminders.METHOD_ALERT);
                if (resolved == id) reminder.withValueBackReference("event_id", 0); else reminder.withValue("event_id", resolved);
                operations.add(reminder.build());
            }
            var results = resolver.applyBatch(CalendarContract.AUTHORITY, operations);
            long changed = resolved == id ? ContentUris.parseId(results[0].uri) : resolved;
            var readback = event(changed); verify(exception, readback); if (!delete) verifyReminder(call, readback);
            return Map.of("eventId", changed, "event", readback);
        }
        if (!Objects.equals(before.get("revision"), call.argument("revision"))) throw new IllegalArgumentException("事件已变化，请重新查询并确认");
        Uri uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id);
        if (delete) {
            if (resolver.delete(uri, null, null) != 1) throw new IllegalStateException("event delete failed");
            try (Cursor cursor = resolver.query(uri, new String[]{"deleted"}, null, null, null)) {
                if (cursor == null || cursor.moveToFirst() && cursor.getInt(0) == 0) throw new IllegalStateException("delete not verified");
            }
            return Map.of("eventId", id, "status", "DELETED");
        }
        ContentValues values = eventValues(call); ArrayList<ContentProviderOperation> operations = new ArrayList<>();
        operations.add(ContentProviderOperation.newUpdate(uri).withValues(values).withExpectedCount(1).build());
        if (call.argument("reminderMinutes") != null) {
            operations.add(ContentProviderOperation.newDelete(CalendarContract.Reminders.CONTENT_URI).withSelection("event_id=?", new String[]{Long.toString(id)}).build());
            operations.add(ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI).withValue("event_id", id)
                    .withValue("minutes", reminder(call)).withValue("method", CalendarContract.Reminders.METHOD_ALERT).build());
        }
        resolver.applyBatch(CalendarContract.AUTHORITY, operations); Map<String, Object> readback = event(id); verify(values, readback); verifyReminder(call, readback);
        return Map.of("eventId", id, "event", readback);
    }
    private ContentValues eventValues(ToolCall call) {
        long start = number(call, "startMillis"), end = number(call, "endMillis");
        if (start <= 0 || end <= start) throw new IllegalArgumentException("事件结束必须晚于开始");
        String zone = ZoneId.of(string(call, "timeZone", 80)).getId(); boolean allDay = Boolean.TRUE.equals(call.argument("allDay"));
        if (allDay && (start % 86_400_000 != 0 || end % 86_400_000 != 0 || !zone.equals("UTC"))) throw new IllegalArgumentException("全天事件使用 UTC 日期边界");
        ContentValues values = new ContentValues(); values.put("title", string(call, "title", 200)); values.put("dtstart", start);
        values.put("eventTimezone", zone); values.put("allDay", allDay ? 1 : 0);
        String recurrence = call.argument("rrule") == null ? "" : call.argument("rrule").toString();
        if (!recurrence.isEmpty() && !recurrence.matches("FREQ=(DAILY|WEEKLY|MONTHLY|YEARLY)(;[A-Z]+=[A-Z0-9,+-]+)*")) throw new IllegalArgumentException("无效重复规则");
        if (recurrence.isEmpty()) { values.put("dtend", end); values.putNull("duration"); values.putNull("rrule"); }
        else { values.putNull("dtend"); values.put("duration", "PT" + ((end - start) / 1000) + "S"); values.put("rrule", recurrence); }
        for (String field : List.of("description", "location")) if (call.argument(field) != null) values.put(field.equals("location") ? "eventLocation" : field, call.argument(field).toString());
        return values;
    }
    private static void verifyReminder(ToolCall call, Map<String, Object> actual) {
        if (call.argument("reminderMinutes") != null && !actual.get("reminders").equals(List.of(reminder(call)))) {
            throw new IllegalStateException("reminder readback mismatch");
        }
    }
    private static void verify(ContentValues expected, Map<String, Object> actual) {
        for (String key : expected.keySet()) {
            Object want = expected.get(key), got = actual.get(key);
            if (want == null) { if (got != null && !got.toString().isEmpty()) throw new IllegalStateException("readback mismatch"); }
            else if (got == null || !want.toString().equals(got.toString())) throw new IllegalStateException("readback mismatch");
        }
    }
    private ToolResult clock(ToolCall call, long began) {
        Intent intent;
        switch (call.getCapabilityName()) {
            case "clock.set_alarm" -> {
                long hour = number(call, "hour"), minute = number(call, "minute");
                if (hour > 23 || minute > 59) throw new IllegalArgumentException("闹钟时分超出范围");
                intent = new Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, (int) hour)
                        .putExtra(AlarmClock.EXTRA_MINUTES, (int) minute).putExtra(AlarmClock.EXTRA_MESSAGE, string(call, "label", 80));
                int mask = (int) optionalNumber(call, "weekdaysMask", 0); if ((mask & ~127) != 0) throw new IllegalArgumentException("无效星期");
                ArrayList<Integer> days = new ArrayList<>(); for (int day = 0; day < 7; day++) if ((mask & 1 << day) != 0) days.add(day == 6 ? Calendar.SUNDAY : day + Calendar.MONDAY);
                if (!days.isEmpty()) intent.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, days);
            }
            case "clock.set_timer" -> {
                long seconds = number(call, "seconds"); if (seconds < 1 || seconds > 86_400) throw new IllegalArgumentException("倒计时应在 1 秒到 24 小时内");
                intent = new Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, (int) seconds).putExtra(AlarmClock.EXTRA_MESSAGE, string(call, "label", 80));
            }
            case "clock.open" -> intent = new Intent(AlarmClock.ACTION_SHOW_ALARMS);
            default -> throw new IllegalArgumentException("未知时钟能力");
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (intent.resolveActivity(context.getPackageManager()) == null) throw new IllegalArgumentException("设备没有可处理的时钟应用");
        context.startActivity(intent);
        return new ToolResult(ToolResult.Status.EXECUTION_UNKNOWN, call.getCapabilityName(), "已委托系统时钟，请在时钟应用中确认结果",
                Map.of("status", "DELEGATED_UNVERIFIED", "verified", false), false, elapsed(began));
    }
    private void requirePermission(String permission) { if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) throw new SecurityException("permission unavailable"); }
    private static String value(Cursor cursor, int column) { return cursor.isNull(column) ? "" : cursor.getString(column); }
    private static long number(ToolCall call, String key) {
        Object value = call.argument(key); if (!(value instanceof Number n) || n.doubleValue() != n.longValue() || n.longValue() < 0) throw new IllegalArgumentException("无效参数：" + key); return n.longValue();
    }
    private static long optionalNumber(ToolCall call, String key, long fallback) { return call.argument(key) == null ? fallback : number(call, key); }
    private static String string(ToolCall call, String key, int max) { Object value = call.argument(key); if (!(value instanceof String text) || text.isBlank() || text.length() > max) throw new IllegalArgumentException("无效参数：" + key); return text; }
    private static int reminder(ToolCall call) { long value = number(call, "reminderMinutes"); if (value > 10_080) throw new IllegalArgumentException("提醒提前量不得超过 7 天"); return (int) value; }
    private static long elapsed(long began) { return android.os.SystemClock.elapsedRealtime() - began; }
}
