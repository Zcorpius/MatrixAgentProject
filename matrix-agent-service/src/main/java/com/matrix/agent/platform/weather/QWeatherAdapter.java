package com.matrix.agent.platform.weather;

import com.matrix.agent.identity.AgentRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** GeoAPI city resolution plus current v1 weather endpoints. No raw fix is serialized. */
public final class QWeatherAdapter implements CityResolverPort, WeatherProviderPort {
    private final WeatherHttpPort transport;
    public QWeatherAdapter(WeatherHttpPort transport) { this.transport = transport; }

    @Override public City resolve(LocationProviderPort.Fix fix, AgentRequest request) throws WeatherFailure, InterruptedException {
        String point = String.format(Locale.ROOT, "%.2f,%.2f", fix.longitude(), fix.latitude());
        JSONObject city = lookup(point, false, request);
        return city(city, fix.fixedAtMillis(), "CURRENT_AT_TRIGGER");
    }
    @Override public City resolveId(String cityId, AgentRequest request) throws WeatherFailure, InterruptedException {
        if (cityId == null || !cityId.matches("[0-9A-Za-z_-]{1,32}")) throw new WeatherFailure("CITY_ID_INVALID");
        JSONObject verified = lookup(cityId, true, request);
        return city(verified, 0, "FIXED_CITY");
    }
    private JSONObject lookup(String location, boolean exactId, AgentRequest request) throws WeatherFailure, InterruptedException {
        if (!location.matches("[0-9A-Za-z_,.\\-]{1,64}")) throw new WeatherFailure("CITY_QUERY_INVALID");
        JSONObject data = transport.get("/geo/v2/city/lookup", "location=" + location.replace(",", "%2C") + "&number=2", request);
        if (!"200".equals(data.optString("code"))) throw new WeatherFailure("CITY_LOOKUP_FAILED");
        JSONArray rows = data.optJSONArray("location");
        if (rows == null || rows.length() == 0 || rows.optJSONObject(0) == null) throw new WeatherFailure("CITY_NOT_FOUND");
        JSONObject first = rows.optJSONObject(0);
        if (exactId) {
            if (!location.equals(first.optString("id"))) throw new WeatherFailure("CITY_ID_MISMATCH");
        } else if (rows.length() > 1) {
            JSONObject second = rows.optJSONObject(1);
            if (second != null && !first.optString("adm2").equals(second.optString("adm2")))
                throw new WeatherFailure("CITY_AMBIGUOUS");
        }
        return first;
    }
    static City city(JSONObject value, long locatedAt, String source) throws WeatherFailure {
        String id = value.optString("id"), name = value.optString("name"), district = value.optString("adm2");
        if (!id.matches("[0-9A-Za-z_-]{1,32}") || name.isBlank() || name.length() > 80) throw new WeatherFailure("CITY_RESPONSE_INVALID");
        try { return new City(id, name, district.length() > 80 ? district.substring(0, 80) : district,
                ZoneId.of(value.optString("tz")), locatedAt, source); }
        catch (RuntimeException invalid) { throw new WeatherFailure("CITY_ZONE_INVALID"); }
    }

    @Override public Map<String, Object> today(City city, LocalDate date, AgentRequest request)
            throws WeatherFailure, InterruptedException {
        if (!city.id().matches("[0-9A-Za-z_-]{1,32}")) throw new WeatherFailure("CITY_ID_INVALID");
        JSONObject metadata = lookup(city.id(), true, request);
        if (!city.id().equals(metadata.optString("id"))) throw new WeatherFailure("CITY_ID_MISMATCH");
        City verifiedCity = city(metadata, city.locatedAtMillis(), city.source());
        if (!verifiedCity.zone().equals(city.zone())) throw new WeatherFailure("CITY_ZONE_MISMATCH");
        double lat = coordinate(metadata, "lat", -90, 90), lon = coordinate(metadata, "lon", -180, 180);
        String point = String.format(Locale.ROOT, "%.2f/%.2f", lat, lon);
        JSONObject current = null, daily = null;
        WeatherFailure currentFailure = null, dailyFailure = null;
        try { current = transport.get("/weather/v1/current/" + point, "lang=zh", request); }
        catch (WeatherFailure failed) { currentFailure = failed; }
        try { daily = transport.get("/weather/v1/daily/" + point, "days=2&lang=zh", request); }
        catch (WeatherFailure failed) { dailyFailure = failed; }
        if (current == null && daily == null) throw dailyFailure == null ? currentFailure : dailyFailure;
        long retrievedAt = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", "QWeather"); result.put("attribution", "和风天气（QWeather）");
        result.put("attributionUrl", "https://www.qweather.com");
        List<String> attributionLinks = attributions(current, daily);
        if (!attributionLinks.isEmpty()) result.put("attributionLinks", attributionLinks);
        result.put("cityId", city.id()); result.put("cityName", verifiedCity.name()); result.put("zoneId", city.zone().getId());
        result.put("localDate", date.toString()); result.put("retrievedAt", retrievedAt);
        if (current != null && date.equals(Instant.ofEpochMilli(retrievedAt).atZone(city.zone()).toLocalDate())) {
            JSONObject condition = current.optJSONObject("condition");
            if (condition != null) putShort(result, "conditionText", condition.optString("text"), 40);
            JSONObject temperature = current.optJSONObject("temperature");
            if (temperature != null && "°C".equals(temperature.optString("unit"))) putTemperature(result, "temperatureNowC", temperature);
            // v1 current does not carry an observation timestamp: retrieval time is reported separately.
        }
        if (daily == null) return forecastUnavailable(result, dailyFailure);
        try { applyForecast(result, daily, city.zone(), date, retrievedAt); }
        catch (WeatherFailure invalidForecast) { return forecastUnavailable(result, invalidForecast); }
        if (currentFailure != null) {
            result.put("partial", true); result.put("missing", "CURRENT"); result.put("missingReason", currentFailure.code());
        }
        if (!result.containsKey("conditionText") && !result.containsKey("temperatureNowC")
                && !result.containsKey("forecastCondition") && !result.containsKey("minC") && !result.containsKey("maxC"))
            throw new WeatherFailure("WEATHER_CONTENT_EMPTY");
        return Map.copyOf(result);
    }
    private static Map<String, Object> forecastUnavailable(Map<String, Object> result, WeatherFailure failure) throws WeatherFailure {
        if (!result.containsKey("temperatureNowC") && !result.containsKey("conditionText"))
            throw failure == null ? new WeatherFailure("WEATHER_CONTENT_EMPTY") : failure;
        result.put("partial", true); result.put("missing", "FORECAST");
        if (failure != null) result.put("missingReason", failure.code());
        return Map.copyOf(result);
    }
    private static void applyForecast(Map<String, Object> result, JSONObject daily, ZoneId zone, LocalDate date, long retrievedAt)
            throws WeatherFailure {
        JSONArray days = daily.optJSONArray("days");
        if (days == null || days.length() == 0) throw new WeatherFailure("WEATHER_FORECAST_MISSING");
        JSONObject selected = null;
        for (int index = 0; index < days.length(); index++) {
            JSONObject candidate = days.optJSONObject(index);
            if (candidate == null) continue;
            try {
                Instant start = Instant.parse(candidate.optString("forecastStartTime"));
                if (start.atZone(zone).toLocalDate().equals(date)) { selected = candidate; break; }
            } catch (RuntimeException invalid) { throw new WeatherFailure("WEATHER_FORECAST_INVALID"); }
        }
        if (selected == null) throw new WeatherFailure("WEATHER_DATE_MISMATCH");
        try {
            Instant from = Instant.parse(selected.optString("forecastStartTime"));
            Instant until = Instant.parse(selected.optString("forecastEndTime"));
            if (!until.isAfter(from) || until.isBefore(Instant.ofEpochMilli(retrievedAt))) throw new WeatherFailure("WEATHER_FORECAST_STALE");
            result.put("validFrom", from.toEpochMilli()); result.put("validUntil", until.toEpochMilli());
        } catch (WeatherFailure known) { throw known; }
        catch (RuntimeException invalid) { throw new WeatherFailure("WEATHER_FORECAST_INVALID"); }
        putTemperature(result, "minC", selected.optJSONObject("temperatureMin"));
        putTemperature(result, "maxC", selected.optJSONObject("temperatureMax"));
        JSONObject daytime = selected.optJSONObject("daytime");
        if (daytime != null) {
            JSONObject condition = daytime.optJSONObject("condition");
            if (condition != null) putShort(result, "forecastCondition", condition.optString("text"), 40);
            JSONObject precipitation = daytime.optJSONObject("precipitation");
            if (precipitation != null && precipitation.has("probability")) {
                double value = precipitation.optDouble("probability", Double.NaN);
                if (Double.isFinite(value) && value >= 0 && value <= 1) result.put("precipitationProbability", value);
            }
        }
    }
    private static double coordinate(JSONObject data, String key, double min, double max) throws WeatherFailure {
        try {
            double number = Double.parseDouble(data.optString(key));
            if (Double.isFinite(number) && number >= min && number <= max) return number;
        } catch (RuntimeException ignored) { }
        throw new WeatherFailure("CITY_COORDINATE_INVALID");
    }
    private static void putTemperature(Map<String, Object> result, String key, JSONObject data) {
        if (data == null || !"°C".equals(data.optString("unit"))) return;
        double value = data.optDouble("value", Double.NaN);
        if (Double.isFinite(value) && value >= -100 && value <= 65) result.put(key, value);
    }
    private static void putShort(Map<String, Object> result, String key, String text, int limit) {
        if (text != null && !text.isBlank() && text.length() <= limit) result.put(key, text);
    }
    private static List<String> attributions(JSONObject... responses) {
        LinkedHashSet<String> links = new LinkedHashSet<>();
        for (JSONObject response : responses) {
            if (response == null) continue;
            JSONObject metadata = response.optJSONObject("metadata");
            JSONArray values = metadata == null ? null : metadata.optJSONArray("attributions");
            if (values == null) continue;
            for (int index = 0; index < Math.min(values.length(), 4); index++) {
                String value = values.optString(index, "");
                if (value.length() > 160) continue;
                try {
                    var url = java.net.URI.create(value);
                    if ("https".equalsIgnoreCase(url.getScheme()) && url.getHost() != null
                            && url.getUserInfo() == null && url.getQuery() == null && url.getFragment() == null)
                        links.add(value);
                } catch (RuntimeException ignored) { }
            }
        }
        return List.copyOf(links);
    }
}
