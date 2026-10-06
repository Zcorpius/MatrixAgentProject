package com.matrix.agent.platform.weather;

import com.matrix.agent.identity.AgentRequest;
import java.time.LocalDate;
import java.util.Map;

public interface WeatherProviderPort {
    /** Missing observations are omitted, not guessed. Returned data is city-level only. */
    Map<String, Object> today(CityResolverPort.City city, LocalDate date, AgentRequest request)
            throws WeatherFailure, InterruptedException;
}
