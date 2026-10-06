package com.matrix.agent.schedule.workflow;

import static com.matrix.agent.api.schedule.ScheduleCodes.SUCCEEDED;
import static com.matrix.agent.api.schedule.ScheduleCodes.PARTIAL;
import com.matrix.agent.data.schedule.ScheduleStepEntity;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.json.JSONObject;

/** Strict formatting of verified step facts; external strings never control workflow behavior. */
public final class WeatherBriefComposer {
    private WeatherBriefComposer() { }
    public static String compose(List<ScheduleStepEntity> steps) { return compose(steps, true); }
    public static String forSpeech(List<ScheduleStepEntity> steps) { return compose(steps, false); }
    private static String compose(List<ScheduleStepEntity> steps, boolean includeLinks) {
        ScheduleStepEntity city = step(steps, "resolve_city"), weather = step(steps, "fetch_weather");
        if (city.state != SUCCEEDED) return "天气提醒已到点。无法确定本次城市，未查询天气（" + reason(city.reason) + "）。";
        try {
        JSONObject cityData = new JSONObject(city.outputJson);
        String name = clean(cityData.optString("cityName", "所在城市"));
        String prefix = "BACKUP_CITY".equals(cityData.optString("source")) ? "当前位置未能确定，改用已授权的备用城市。" : "";
        if (weather.state != SUCCEEDED && weather.state != PARTIAL) return "天气提醒已到点。" + prefix + name + "的天气暂不可用（" + reason(weather.reason) + "）。";
        JSONObject facts = new JSONObject(weather.outputJson);
        if (!cityData.optString("cityId").equals(facts.optString("cityId")))
            throw new IllegalArgumentException("weather city changed across steps");
        StringBuilder text = new StringBuilder(prefix).append(name).append(" ").append(facts.getString("localDate")).append(" 天气：");
        append(text, facts, "conditionText", "当前");
        appendTemperature(text, facts, "temperatureNowC", "当前气温");
        append(text, facts, "forecastCondition", "白天预报");
        appendTemperature(text, facts, "minC", "最低");
        appendTemperature(text, facts, "maxC", "最高");
        if (facts.has("precipitationProbability")) text.append("；白天降水概率 ")
                .append(Math.round(facts.getDouble("precipitationProbability") * 100)).append("%");
        long retrieved = facts.getLong("retrievedAt");
        ZoneId zone = ZoneId.of(facts.getString("zoneId"));
        if (facts.optBoolean("partial")) text.append("部分数据未取得")
                .append(facts.has("missingReason") ? "（" + reason(facts.optString("missingReason")) + "）" : "").append("；");
        text.append("。查询于 ").append(DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(Instant.ofEpochMilli(retrieved)))
                .append(includeLinks ? "，来源：和风天气（QWeather，https://www.qweather.com）"
                        : "，来源：和风天气（QWeather）");
        if (includeLinks) {
            org.json.JSONArray links = facts.optJSONArray("attributionLinks");
            if (links != null) for (int index = 0; index < Math.min(links.length(), 3); index++) {
                String link = safeAttribution(links.optString(index, ""));
                if (!link.isEmpty()) text.append("；数据归因 ").append(link);
            }
        }
        text.append("。");
        return text.toString();
        } catch (org.json.JSONException malformed) { throw new IllegalArgumentException("invalid verified weather checkpoint", malformed); }
    }
    private static ScheduleStepEntity step(List<ScheduleStepEntity> rows, String id) {
        return rows.stream().filter(row -> row.stepId.equals(id)).findFirst().orElseThrow();
    }
    private static void append(StringBuilder text, JSONObject facts, String key, String label) {
        String value = facts.optString(key, "");
        if (!value.isEmpty()) text.append(label).append(" ").append(clean(value)).append("；");
    }
    private static void appendTemperature(StringBuilder text, JSONObject facts, String key, String label) {
        if (facts.has(key)) text.append(label).append(" ").append(Math.round(facts.optDouble(key))).append("℃；");
    }
    private static String clean(String value) {
        String stripped = value.replaceAll("[\\p{Cntrl}\\n\\r]", " ").strip();
        return stripped.length() > 80 ? stripped.substring(0, 80) : stripped;
    }
    private static String safeAttribution(String value) {
        if (value.length() > 160) return "";
        try {
            var uri = java.net.URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                    && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null
                    ? value : "";
        } catch (RuntimeException invalid) { return ""; }
    }
    private static String reason(String code) {
        return switch (code) {
            case "LOCATION_PERMISSION_DENIED", "BACKGROUND_LOCATION_DENIED", "LOCATION_NOT_AUTHORIZED" -> "定位授权已撤销";
            case "WEATHER_AUTHORIZATION_REVOKED", "WEATHER_NETWORK_NOT_AUTHORIZED" -> "天气网络授权已撤销";
            case "LOCATION_DISABLED" -> "定位服务已关闭";
            case "LOCATION_PROVIDER_UNCERTIFIED" -> "设备城市级后台定位源不可用";
            case "LOCATION_STALE", "LOCATION_INACCURATE", "CITY_AMBIGUOUS" -> "位置不够准确";
            case "WEATHER_NOT_CONFIGURED" -> "天气服务未配置";
            case "WEATHER_CREDENTIAL_REJECTED" -> "天气服务凭据无效";
            case "WEATHER_RATE_LIMITED" -> "天气服务限流";
            case "WEATHER_TIMEOUT", "LOCATION_UNAVAILABLE" -> "查询超时";
            case "WEATHER_NETWORK_UNAVAILABLE" -> "网络不可用";
            default -> "本次查询未完成";
        };
    }
}
