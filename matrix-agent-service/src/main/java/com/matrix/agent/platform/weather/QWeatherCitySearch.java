package com.matrix.agent.platform.weather;

import com.matrix.agent.identity.AgentRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Explicit city picker. It returns only provider-verified IDs and time zones to the Launcher. */
public final class QWeatherCitySearch {
    private final WeatherCityLookupPort transport;
    public QWeatherCitySearch(WeatherCityLookupPort transport) { this.transport = transport; }

    public List<CityResolverPort.City> find(String query, AgentRequest request)
            throws WeatherFailure, InterruptedException {
        String normalized = query == null ? "" : query.strip();
        if (normalized.isEmpty() || normalized.length() > 40
                || !normalized.matches("[\\p{IsHan}A-Za-z0-9 .'-]+"))
            throw new WeatherFailure("CITY_QUERY_INVALID");
        String encoded;
        try { encoded = URLEncoder.encode(normalized, StandardCharsets.UTF_8.name()); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
        JSONObject data = transport.searchCity("location=" + encoded + "&number=5&lang=zh", request);
        if (!"200".equals(data.optString("code"))) throw new WeatherFailure("CITY_LOOKUP_FAILED");
        JSONArray rows = data.optJSONArray("location");
        if (rows == null || rows.length() == 0) throw new WeatherFailure("CITY_NOT_FOUND");
        List<CityResolverPort.City> choices = new ArrayList<>();
        for (int index = 0; index < Math.min(5, rows.length()); index++) {
            JSONObject row = rows.optJSONObject(index);
            if (row != null) choices.add(QWeatherAdapter.city(row, 0, "FIXED_CITY"));
        }
        if (choices.isEmpty()) throw new WeatherFailure("CITY_NOT_FOUND");
        return List.copyOf(choices);
    }
}
