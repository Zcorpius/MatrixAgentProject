package com.matrix.agent.schedule.domain;

import org.json.JSONException;
import org.json.JSONObject;

/** Versioned, verified source data. Calendar titles never become action instructions. */
public record CalendarSnapshot(long startMillis, long endMillis, String revision, String title, boolean allDay) {
    public CalendarSnapshot {
        if (startMillis <= 0 || endMillis < startMillis || revision == null || revision.isEmpty())
            throw new IllegalArgumentException("invalid calendar snapshot");
    }
    public String encode() {
        try { return new JSONObject().put("version", 1).put("start", startMillis).put("end", endMillis)
                .put("revision", revision).put("title", title).put("allDay", allDay).toString(); }
        catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
    public static CalendarSnapshot decode(String value) {
        try {
            JSONObject object = new JSONObject(value);
            if (object.getInt("version") != 1) throw new IllegalArgumentException("unknown calendar snapshot version");
            return new CalendarSnapshot(object.getLong("start"), object.getLong("end"), object.getString("revision"),
                    object.getString("title"), object.getBoolean("allDay"));
        } catch (JSONException malformed) { throw new IllegalArgumentException("invalid calendar snapshot", malformed); }
    }
}
