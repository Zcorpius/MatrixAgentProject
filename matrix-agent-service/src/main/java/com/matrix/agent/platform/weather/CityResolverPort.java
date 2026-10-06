package com.matrix.agent.platform.weather;

import com.matrix.agent.identity.AgentRequest;
import java.time.ZoneId;

public interface CityResolverPort {
    record City(String id, String name, String district, ZoneId zone, long locatedAtMillis, String source) { }
    City resolve(LocationProviderPort.Fix fix, AgentRequest request) throws WeatherFailure, InterruptedException;
    City resolveId(String cityId, AgentRequest request) throws WeatherFailure, InterruptedException;
}
