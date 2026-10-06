package com.matrix.agent.schedule.workflow;

import com.matrix.agent.api.schedule.ScheduleAction;
import com.matrix.agent.task.capability.WeatherCapabilities;
import java.util.List;
import org.json.JSONObject;

/** Immutable first version: optional data steps always converge on a deterministic explanation. */
public final class WeatherWorkflow {
    public static final String ID = "daily_weather_current";
    private WeatherWorkflow() { }

    public static WorkflowTemplate template() {
        return new WorkflowTemplate(ID, 1, "当前位置天气简报",
                "到点提醒后解析城市，读取当天实况与预报，更新同一提醒；定位或网络失败时说明原因。",
                List.of(new WorkflowTemplate.Step("resolve_city", "解析本次城市", WorkflowTemplate.Kind.TOOL,
                                List.of(), false, true, WeatherCapabilities.RESOLVE_CITY, 8_000, 0, ""),
                        new WorkflowTemplate.Step("fetch_weather", "获取当地天气", WorkflowTemplate.Kind.TOOL,
                                List.of("resolve_city"), false, true, WeatherCapabilities.TODAY, 16_000, 1, ""),
                        new WorkflowTemplate.Step("compose", "编写确定性简报", WorkflowTemplate.Kind.TRANSFORM,
                                List.of("resolve_city", "fetch_weather"), true, true, "", 2_000, 0, ""),
                        new WorkflowTemplate.Step("deliver", "更新通知与可选播报", WorkflowTemplate.Kind.DELIVER,
                                List.of("compose"), true, false, "", 10_000, 0, "schedule-notifications")),
                com.matrix.agent.identity.ExecutionProfile.WEATHER);
    }

    public static boolean isWeather(WorkflowTemplate template) { return ID.equals(template.id()); }

    public static void validate(ScheduleAction action, JSONObject parameters) {
        String mode = parameters.optString("mode", "");
        if (!mode.equals("CURRENT_AT_TRIGGER") && !mode.equals("FIXED_CITY")) throw new IllegalArgumentException("请选择当前位置或固定城市");
        for (var keys = parameters.keys(); keys.hasNext();) {
            if (!List.of("mode", "fixedCityId", "fixedCityName", "fixedCityZone", "backupCityId", "backupCityName",
                    "backupCityZone", "allowLocation", "allowWeatherNetwork").contains(keys.next()))
                throw new IllegalArgumentException("天气模板参数不合法");
        }
        if (!parameters.has("allowWeatherNetwork") || !parameters.has("allowLocation")
                || parameters.optBoolean("allowWeatherNetwork") != action.allowNetwork)
            throw new IllegalArgumentException("天气授权字段不一致");
        if (mode.equals("FIXED_CITY")) {
            if (parameters.optBoolean("allowLocation")) throw new IllegalArgumentException("固定城市模式不能申请当前位置");
            validateCity(parameters, "fixedCity");
            if (parameters.has("backupCityId") || parameters.has("backupCityName") || parameters.has("backupCityZone"))
                throw new IllegalArgumentException("固定城市模式不接受备用城市");
        } else if (parameters.has("fixedCityId") || parameters.has("fixedCityName") || parameters.has("fixedCityZone")) {
            throw new IllegalArgumentException("当前位置模式暂不接受备用城市");
        } else if (parameters.has("backupCityId") || parameters.has("backupCityName") || parameters.has("backupCityZone")) {
            if (!parameters.optBoolean("allowLocation")) throw new IllegalArgumentException("备用城市不能代替当前位置授权");
            validateCity(parameters, "backupCity");
        }
    }
    private static void validateCity(JSONObject parameters, String prefix) {
        String id = parameters.optString(prefix + "Id", ""), name = parameters.optString(prefix + "Name", ""),
                zone = parameters.optString(prefix + "Zone", "");
        if (!id.matches("[0-9A-Za-z_-]{1,32}") || name.isBlank() || name.length() > 80 || zone.length() > 80)
            throw new IllegalArgumentException("请从城市搜索中选择明确的城市");
        try { java.time.ZoneId.of(zone); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("城市时区无效"); }
    }
}
