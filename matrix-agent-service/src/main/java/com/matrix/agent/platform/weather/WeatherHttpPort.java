package com.matrix.agent.platform.weather;

import com.matrix.agent.identity.AgentRequest;
import org.json.JSONObject;

@FunctionalInterface
public interface WeatherHttpPort {
    JSONObject get(String path, String query, AgentRequest request) throws WeatherFailure, InterruptedException;
}
