package com.matrix.agent.schedule.workflow;

import static com.matrix.agent.api.schedule.ScheduleCodes.*;
import static org.junit.Assert.*;
import com.matrix.agent.api.schedule.ScheduleAction;
import com.matrix.agent.data.schedule.ScheduleStepEntity;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public final class WeatherWorkflowTest {
    @Test public void missingConsentCanBeSavedAsDraftButCannotWidenCapabilities() {
        String parameters = "{\"mode\":\"CURRENT_AT_TRIGGER\",\"allowLocation\":false,\"allowWeatherNetwork\":false}";
        ScheduleAction draft = new ScheduleAction(WORKFLOW, "天气", WeatherWorkflow.ID, 1, parameters,
                List.of("location.resolve_city", "weather.today"), false, false);
        WorkflowCatalog.validate(draft);
        assertThrows(IllegalArgumentException.class, () -> WorkflowCatalog.validate(new ScheduleAction(WORKFLOW,
                "天气", WeatherWorkflow.ID, 1, parameters, List.of("weather.today"), false, false)));
        assertEquals(45_000, WeatherWorkflow.template().profile().maxActiveMillis());
    }
    @Test public void missingLocationStillCreatesDeterministicExplanation() {
        ScheduleStepEntity city = new ScheduleStepEntity(); city.stepId = "resolve_city";
        city.state = FAILED; city.reason = "LOCATION_PERMISSION_DENIED";
        ScheduleStepEntity fetch = new ScheduleStepEntity(); fetch.stepId = "fetch_weather";
        fetch.state = SKIPPED; fetch.reason = "CITY_UNAVAILABLE";
        String text = WeatherBriefComposer.compose(List.of(city, fetch));
        assertTrue(text.contains("已到点")); assertTrue(text.contains("定位授权已撤销"));
        assertFalse(text.contains("℃"));
    }
    @Test public void cityMismatchCannotBeFormattedAsWeather() throws Exception {
        ScheduleStepEntity city = new ScheduleStepEntity(); city.stepId = "resolve_city"; city.state = SUCCEEDED;
        city.outputJson = new JSONObject().put("cityId", "one").put("cityName", "上海").toString();
        ScheduleStepEntity fetch = new ScheduleStepEntity(); fetch.stepId = "fetch_weather"; fetch.state = SUCCEEDED;
        fetch.outputJson = new JSONObject().put("cityId", "two").toString();
        assertThrows(IllegalArgumentException.class, () -> WeatherBriefComposer.compose(List.of(city, fetch)));
    }
    @Test public void explicitBackupIsValidatedAndProminentlyLabeled() throws Exception {
        String parameters = "{\"mode\":\"CURRENT_AT_TRIGGER\",\"allowLocation\":true,\"allowWeatherNetwork\":true,"
                + "\"backupCityId\":\"101020100\",\"backupCityName\":\"上海\",\"backupCityZone\":\"Asia/Shanghai\"}";
        ScheduleAction action = new ScheduleAction(WORKFLOW, "天气", WeatherWorkflow.ID, 1, parameters,
                List.of("location.resolve_city", "weather.today"), true, false);
        WorkflowCatalog.validate(action);
        JSONObject missingZone = new JSONObject(parameters); missingZone.remove("backupCityZone");
        assertThrows(IllegalArgumentException.class, () -> WeatherWorkflow.validate(action, missingZone));
        ScheduleStepEntity city = new ScheduleStepEntity(); city.stepId = "resolve_city"; city.state = SUCCEEDED;
        city.outputJson = new JSONObject().put("cityId", "101020100").put("cityName", "上海")
                .put("source", "BACKUP_CITY").toString();
        ScheduleStepEntity fetch = new ScheduleStepEntity(); fetch.stepId = "fetch_weather"; fetch.state = SUCCEEDED;
        fetch.outputJson = new JSONObject().put("cityId", "101020100").put("localDate", "2026-10-06")
                .put("zoneId", "Asia/Shanghai").put("retrievedAt", 1_000L)
                .put("conditionText", "晴").toString();
        assertTrue(WeatherBriefComposer.compose(List.of(city, fetch)).startsWith("当前位置未能确定，改用已授权的备用城市"));
        assertTrue(WeatherBriefComposer.compose(List.of(city, fetch)).contains("https://www.qweather.com"));
        assertFalse(WeatherBriefComposer.forSpeech(List.of(city, fetch)).contains("https://"));
    }
}
