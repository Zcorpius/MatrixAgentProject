package com.matrix.agent.platform.weather;

import com.matrix.agent.identity.AgentRequest;
import org.json.JSONObject;

/** Foreground city-search HTTP boundary; no arbitrary path or URL is exposed. */
@FunctionalInterface
public interface WeatherCityLookupPort {
    JSONObject searchCity(String encodedQuery, AgentRequest request) throws WeatherFailure, InterruptedException;
}
