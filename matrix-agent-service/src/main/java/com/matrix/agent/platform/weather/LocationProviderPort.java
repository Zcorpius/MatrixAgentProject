package com.matrix.agent.platform.weather;

import com.matrix.agent.identity.AgentRequest;

/** Raw coordinates stay inside a single provider invocation and never enter a workflow checkpoint. */
public interface LocationProviderPort {
    record Fix(double latitude, double longitude, float accuracyMeters, long fixedAtMillis) { }
    Fix current(AgentRequest request) throws WeatherFailure, InterruptedException;
}
