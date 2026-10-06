package com.matrix.agent.platform.weather;

import static org.junit.Assert.*;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import java.time.ZoneId;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class QWeatherCitySearchTest {
    @Test public void pickerReturnsVerifiedCityIdsWithoutCoordinates() throws Exception {
        var request = new AgentRequest("选择城市", Actor.DRIVER);
        JSONObject response = new JSONObject().put("code", "200").put("location", new JSONArray()
                .put(new JSONObject().put("id", "101020100").put("name", "上海")
                        .put("adm2", "上海").put("tz", "Asia/Shanghai")
                        .put("lat", "31.23").put("lon", "121.47")));
        WeatherCityLookupPort transport = (query, ignored) -> {
            assertEquals("location=%E4%B8%8A%E6%B5%B7&number=5&lang=zh", query);
            return response;
        };
        var cities = new QWeatherCitySearch(transport).find("上海", request);
        assertEquals(1, cities.size());
        assertEquals("101020100", cities.get(0).id());
        assertEquals(ZoneId.of("Asia/Shanghai"), cities.get(0).zone());
        assertEquals("FIXED_CITY", cities.get(0).source());
        assertThrows(WeatherFailure.class, () -> new QWeatherCitySearch(transport).find("https://other.example/", request));
    }
}
