package com.matrix.agent.platform.weather;

import static org.junit.Assert.*;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Contract fixtures use the current v1 shape; no real credential or network is needed. */
public final class QWeatherAdapterTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final AgentRequest REQUEST = new AgentRequest("weather fixture", Actor.DRIVER);

    @Test public void cityResolutionDoesNotReturnCoordinatesAndForecastKeepsLocalDate() throws Exception {
        LocalDate date = LocalDate.now(ZONE);
        QWeatherAdapter adapter = new QWeatherAdapter(fixtures(date, false));
        var city = adapter.resolve(new LocationProviderPort.Fix(31.23, 121.47, 800, System.currentTimeMillis()), REQUEST);
        assertEquals("101020100", city.id());
        Map<String, Object> weather = adapter.today(city, date, REQUEST);
        assertEquals(date.toString(), weather.get("localDate"));
        assertEquals("上海", weather.get("cityName"));
        assertEquals(28d, (Double) weather.get("maxC"), 0.01);
        assertEquals(0.4d, (Double) weather.get("precipitationProbability"), 0.01);
        assertEquals(java.util.List.of("https://developer.qweather.com/attribution.html"), weather.get("attributionLinks"));
        assertFalse(weather.containsKey("latitude"));
        assertFalse(weather.containsKey("longitude"));
        assertFalse(weather.containsKey("observationTime"));
    }
    @Test public void currentFailureReturnsOnlyVerifiedForecastAsPartial() throws Exception {
        LocalDate date = LocalDate.now(ZONE);
        var city = new CityResolverPort.City("101020100", "上海", "上海", ZONE, 0, "FIXED_CITY");
        Map<String, Object> weather = new QWeatherAdapter(fixtures(date, true)).today(city, date, REQUEST);
        assertEquals(true, weather.get("partial"));
        assertEquals("WEATHER_TIMEOUT", weather.get("missingReason"));
        assertFalse(weather.containsKey("temperatureNowC"));
        assertEquals(20d, (Double) weather.get("minC"), 0.01);
    }
    @Test public void malformedForecastKeepsOnlyVerifiedCurrentConditions() throws Exception {
        LocalDate date = LocalDate.now(ZONE);
        WeatherHttpPort fixtures = fixtures(date, false);
        JSONObject invalidDaily = new JSONObject().put("days", new JSONArray());
        WeatherHttpPort malformedDaily = (path, query, request) -> path.startsWith("/weather/v1/daily/")
                ? invalidDaily : fixtures.get(path, query, request);
        var city = new CityResolverPort.City("101020100", "上海", "", ZONE, 0, "FIXED_CITY");
        Map<String, Object> weather = new QWeatherAdapter(malformedDaily).today(city, date, REQUEST);
        assertEquals(true, weather.get("partial"));
        assertEquals("FORECAST", weather.get("missing"));
        assertEquals(25d, (Double) weather.get("temperatureNowC"), 0.01);
        assertFalse(weather.containsKey("minC"));
    }
    @Test public void mismatchedCityIdentityFailsClosed() throws Exception {
        LocalDate date = LocalDate.now(ZONE);
        var city = new CityResolverPort.City("other-id", "上海", "", ZONE, 0, "FIXED_CITY");
        WeatherFailure failure = assertThrows(WeatherFailure.class,
                () -> new QWeatherAdapter(fixtures(date, false)).today(city, date, REQUEST));
        assertEquals("CITY_ID_MISMATCH", failure.code());
    }
    @Test public void fixedCityIdentityAndZoneComeFromProvider() throws Exception {
        LocalDate date = LocalDate.now(ZONE);
        QWeatherAdapter adapter = new QWeatherAdapter(fixtures(date, false));
        var city = adapter.resolveId("101020100", REQUEST);
        assertEquals("上海", city.name());
        assertEquals(ZONE, city.zone());
        WeatherFailure failure = assertThrows(WeatherFailure.class,
                () -> adapter.today(new CityResolverPort.City(city.id(), city.name(), "", ZoneId.of("UTC"), 0, "FIXED_CITY"), date, REQUEST));
        assertEquals("CITY_ZONE_MISMATCH", failure.code());
    }

    private static WeatherHttpPort fixtures(LocalDate date, boolean failCurrent) throws Exception {
        JSONObject geo = new JSONObject().put("code", "200").put("location", new JSONArray().put(new JSONObject()
                .put("id", "101020100").put("name", "上海").put("adm2", "上海")
                .put("tz", ZONE.getId()).put("lat", "31.23").put("lon", "121.47")));
        JSONObject current = new JSONObject().put("metadata", new JSONObject().put("attributions",
                        new JSONArray().put("https://developer.qweather.com/attribution.html")))
                .put("condition", new JSONObject().put("text", "晴"))
                .put("temperature", new JSONObject().put("value", 25).put("unit", "°C"));
        JSONObject day = new JSONObject().put("forecastStartTime", date.atStartOfDay(ZONE).toInstant().toString())
                .put("forecastEndTime", date.plusDays(1).atStartOfDay(ZONE).toInstant().toString())
                .put("temperatureMin", new JSONObject().put("value", 20).put("unit", "°C"))
                .put("temperatureMax", new JSONObject().put("value", 28).put("unit", "°C"))
                .put("daytime", new JSONObject().put("condition", new JSONObject().put("text", "多云"))
                        .put("precipitation", new JSONObject().put("probability", 0.4)));
        JSONObject daily = new JSONObject().put("days", new JSONArray().put(day));
        return (path, query, request) -> {
            if (path.equals("/geo/v2/city/lookup")) return geo;
            if (path.startsWith("/weather/v1/current/")) {
                if (failCurrent) throw new WeatherFailure("WEATHER_TIMEOUT");
                return current;
            }
            if (path.startsWith("/weather/v1/daily/")) return daily;
            throw new AssertionError("unexpected endpoint: " + path);
        };
    }
}
