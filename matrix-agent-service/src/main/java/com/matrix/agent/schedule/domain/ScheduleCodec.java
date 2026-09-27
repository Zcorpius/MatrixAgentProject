package com.matrix.agent.schedule.domain;

import com.matrix.agent.api.schedule.ScheduleAction;
import com.matrix.agent.api.schedule.ScheduleSpec;
import com.matrix.agent.api.schedule.ScheduleTiming;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Versioned canonical storage encoding. No reflection, Java serialization or executable payloads. */
public final class ScheduleCodec {
    private ScheduleCodec() { }

    public static String spec(ScheduleSpec spec) {
        try {
            ScheduleTiming t = spec.timing;
            ScheduleAction a = spec.action;
            return new JSONObject().put("v", 1).put("title", spec.title)
                    .put("timing", new JSONObject().put("kind", t.kind).put("zone", t.zoneId)
                            .put("at", t.atMillis).put("delay", t.delayMillis).put("time", t.localTime)
                            .put("weekdays", t.weekdaysMask).put("start", t.startDate).put("end", t.endDate)
                            .put("follow", t.followDeviceZone).put("binding", t.calendarBindingId)
                            .put("offset", t.calendarOffsetMillis))
                    .put("action", new JSONObject().put("kind", a.kind).put("text", a.text)
                            .put("template", a.templateId).put("version", a.templateVersion)
                            .put("params", new JSONObject(canonicalObject(a.parametersJson)))
                            .put("capabilities", new JSONArray(a.capabilities))
                            .put("network", a.allowNetwork).put("speak", a.speakResult))
                    .put("grace", spec.graceMillis).put("misfire", spec.misfirePolicy).toString();
        } catch (JSONException badData) {
            throw new IllegalArgumentException("invalid schedule encoding", badData);
        }
    }

    public static ScheduleSpec spec(String encoded) {
        try {
            JSONObject o = new JSONObject(encoded);
            if (o.getInt("v") != 1) throw new IllegalArgumentException("unsupported schedule storage version");
            JSONObject t = o.getJSONObject("timing"), a = o.getJSONObject("action");
            return new ScheduleSpec(o.getString("title"),
                    new ScheduleTiming(t.getInt("kind"), t.getString("zone"), t.getLong("at"),
                            t.getLong("delay"), t.optString("time"), t.getInt("weekdays"),
                            t.optString("start"), t.optString("end"), t.getBoolean("follow"),
                            t.optString("binding"), t.getLong("offset")),
                    new ScheduleAction(a.getInt("kind"), a.optString("text"), a.optString("template"),
                            a.getInt("version"), a.getJSONObject("params").toString(),
                            strings(a.getJSONArray("capabilities")), a.getBoolean("network"), a.getBoolean("speak")),
                    o.getLong("grace"), o.getInt("misfire"));
        } catch (JSONException badData) {
            throw new IllegalArgumentException("invalid schedule storage", badData);
        }
    }

    public static String rule(TimeRule rule) {
        try {
            JSONObject o = new JSONObject().put("v", 1);
            if (rule instanceof TimeRule.Once once) {
                o.put("kind", "once").put("at", once.at().toEpochMilli());
            } else if (rule instanceof TimeRule.AfterDelay delay) {
                o.put("kind", "delay").put("at", delay.wallDeadline().toEpochMilli())
                        .put("elapsed", delay.elapsedDeadline()).put("boot", delay.bootId());
            } else if (rule instanceof TimeRule.Recurring recurring) {
                o.put("kind", "recurring").put("time", recurring.time().toString())
                        .put("zone", recurring.zone().getId()).put("follow", recurring.followDeviceZone())
                        .put("days", new JSONArray(recurring.weekdays().stream().map(DayOfWeek::getValue)
                                .sorted().collect(java.util.stream.Collectors.toList())))
                        .put("start", recurring.start().toString())
                        .put("end", recurring.end() == null ? "" : recurring.end().toString());
            } else if (rule instanceof TimeRule.CalendarOffset binding) {
                o.put("kind", "calendar").put("binding", binding.bindingId()).put("offset", binding.offsetMillis());
            }
            return o.toString();
        } catch (JSONException invalid) { throw new IllegalArgumentException("invalid time rule", invalid); }
    }

    public static TimeRule rule(String encoded) {
        try {
            JSONObject o = new JSONObject(encoded);
            if (o.getInt("v") != 1) throw new IllegalArgumentException("unsupported time storage version");
            return switch (o.getString("kind")) {
                case "once" -> new TimeRule.Once(Instant.ofEpochMilli(o.getLong("at")));
                case "delay" -> new TimeRule.AfterDelay(Instant.ofEpochMilli(o.getLong("at")),
                        o.getLong("elapsed"), o.getString("boot"));
                case "recurring" -> new TimeRule.Recurring(LocalTime.parse(o.getString("time")),
                        ZoneId.of(o.getString("zone")), weekdays(o.getJSONArray("days")),
                        LocalDate.parse(o.getString("start")), o.getString("end").isEmpty()
                                ? null : LocalDate.parse(o.getString("end")), o.getBoolean("follow"));
                case "calendar" -> new TimeRule.CalendarOffset(o.getString("binding"), o.getLong("offset"));
                default -> throw new IllegalArgumentException("unknown time rule");
            };
        } catch (JSONException invalid) { throw new IllegalArgumentException("invalid time storage", invalid); }
    }

    public static String canonicalObject(String json) {
        try { return canonical(new JSONObject(json == null || json.isBlank() ? "{}" : json), 0).toString(); }
        catch (JSONException invalid) { throw new IllegalArgumentException("parameters must be a JSON object", invalid); }
    }

    private static Object canonical(Object value, int depth) throws JSONException {
        if (depth > 8) throw new IllegalArgumentException("parameters nested too deeply");
        if (value instanceof JSONObject object) {
            JSONObject result = new JSONObject();
            TreeSet<String> keys = new TreeSet<>();
            object.keys().forEachRemaining(keys::add);
            for (String key : keys) result.put(key, canonical(object.get(key), depth + 1));
            return result;
        }
        if (value instanceof JSONArray array) {
            JSONArray result = new JSONArray();
            for (int i = 0; i < array.length(); i++) result.put(canonical(array.get(i), depth + 1));
            return result;
        }
        return value;
    }

    /** Plain Java values for the shared schema validator and providers, including nested arrays/objects. */
    public static java.util.Map<String, Object> arguments(String encoded) {
        try { return objectArguments(new JSONObject(canonicalObject(encoded))); }
        catch (JSONException invalid) { throw new IllegalArgumentException("invalid action parameters", invalid); }
    }
    private static java.util.Map<String, Object> objectArguments(JSONObject object) throws JSONException {
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        for (var keys = object.keys(); keys.hasNext();) {
            String key = keys.next(); result.put(key, argument(object.get(key)));
        }
        return java.util.Collections.unmodifiableMap(result);
    }
    private static Object argument(Object value) throws JSONException {
        if (value == JSONObject.NULL) return null;
        if (value instanceof JSONObject object) return objectArguments(object);
        if (value instanceof JSONArray array) {
            List<Object> values = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) values.add(argument(array.get(i)));
            return java.util.Collections.unmodifiableList(values);
        }
        return value;
    }

    public static String digest(String... values) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
                hash.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                hash.update(bytes);
            }
            byte[] bytes = hash.digest();
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) hex.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    public static List<String> strings(JSONArray array) throws JSONException {
        List<String> result = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) result.add(array.getString(i));
        return List.copyOf(result);
    }

    private static Set<DayOfWeek> weekdays(JSONArray values) throws JSONException {
        java.util.EnumSet<DayOfWeek> days = java.util.EnumSet.noneOf(DayOfWeek.class);
        for (int i = 0; i < values.length(); i++) days.add(DayOfWeek.of(values.getInt(i)));
        return days;
    }
}
