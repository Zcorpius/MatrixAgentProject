package com.matrix.agent.platform.weather;

import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.task.capability.CapabilityProvider;
import com.matrix.agent.task.capability.WeatherCapabilities;
import com.matrix.agent.task.tool.ToolResult;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/** ToolExecutor entry point. Only a frozen automatic step may access city or weather data. */
public final class WeatherCapabilityProvider implements CapabilityProvider {
    private final LocationProviderPort location;
    private final CityResolverPort cities;
    private final WeatherProviderPort weather;
    public WeatherCapabilityProvider(LocationProviderPort location, CityResolverPort cities, WeatherProviderPort weather) {
        this.location = location; this.cities = cities; this.weather = weather;
    }
    @Override public ToolResult execute(AgentRequest request, ToolCall call) {
        long started = System.nanoTime();
        String name = call.getCapabilityName();
        if (!WeatherCapabilities.ALL.contains(name)) return ToolResult.rejected(name, "未知天气能力");
        try {
            authorize(request, name);
            Map<String, Object> result = name.equals(WeatherCapabilities.RESOLVE_CITY)
                    ? city(request, call) : today(request, call);
            authorize(request, name);
            return new ToolResult(ToolResult.Status.SUCCESS, name, "已核验的城市级天气步骤结果", result, true, elapsed(started));
        } catch (WeatherFailure known) {
            ToolResult.Status status = known.code().endsWith("TIMEOUT") ? ToolResult.Status.TIMED_OUT
                    : known.code().endsWith("CANCELLED") ? ToolResult.Status.CANCELLED : ToolResult.Status.EXECUTION_FAILED;
            return new ToolResult(status, name, known.code(), Map.of("reasonCode", known.code()), false, elapsed(started));
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            return new ToolResult(ToolResult.Status.CANCELLED, name, "WEATHER_CANCELLED", Map.of("reasonCode", "WEATHER_CANCELLED"), false, elapsed(started));
        } catch (RuntimeException invalid) {
            return new ToolResult(ToolResult.Status.EXECUTION_FAILED, name, "WEATHER_RESPONSE_INVALID", Map.of("reasonCode", "WEATHER_RESPONSE_INVALID"), false, elapsed(started));
        }
    }
    private static void authorize(AgentRequest request, String capability) throws WeatherFailure {
        if (request.isCancelled() || request.remainingMillis() <= 0) throw new WeatherFailure("WEATHER_CANCELLED");
        var scope = request.getExecutionScope();
        if (!scope.automatic() || !scope.allows(capability) || !scope.rejection().isEmpty())
            throw new WeatherFailure("WEATHER_AUTHORIZATION_REVOKED");
        if (!scope.networkAllowed()) throw new WeatherFailure("WEATHER_NETWORK_NOT_AUTHORIZED");
    }
    private Map<String, Object> city(AgentRequest request, ToolCall call) throws WeatherFailure, InterruptedException {
        String mode = string(call, "mode");
        CityResolverPort.City city;
        String fallbackReason = "";
        String accuracyBand = "";
        if (mode.equals("CURRENT_AT_TRIGGER")) {
            if (!Boolean.TRUE.equals(call.argument("allowLocation"))) throw new WeatherFailure("LOCATION_NOT_AUTHORIZED");
            try {
                LocationProviderPort.Fix fix = location.current(request);
                city = cities.resolve(fix, request);
                accuracyBand = fix.accuracyMeters() <= 1_000 ? "UP_TO_1_KM" : "UP_TO_5_KM";
            } catch (WeatherFailure unavailable) {
                if (java.util.Set.of("LOCATION_PERMISSION_DENIED", "BACKGROUND_LOCATION_DENIED", "LOCATION_NOT_AUTHORIZED")
                        .contains(unavailable.code()) || string(call, "backupCityId").isEmpty()) throw unavailable;
                city = verifiedFixedCity(call, "backupCity", "BACKUP_CITY", request);
                fallbackReason = unavailable.code();
            }
        } else if (mode.equals("FIXED_CITY")) {
            city = verifiedFixedCity(call, "fixedCity", "FIXED_CITY", request);
        } else throw new WeatherFailure("LOCATION_MODE_INVALID");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cityId", city.id()); result.put("cityName", city.name()); result.put("zoneId", city.zone().getId());
        result.put("source", city.source()); result.put("locatedAt", city.locatedAtMillis());
        if (!fallbackReason.isEmpty()) result.put("fallbackReason", fallbackReason);
        if (!accuracyBand.isEmpty()) result.put("accuracyBand", accuracyBand);
        if (!city.district().isBlank()) result.put("district", city.district());
        return result;
    }
    private CityResolverPort.City verifiedFixedCity(ToolCall call, String prefix, String source, AgentRequest request)
            throws WeatherFailure, InterruptedException {
        String id = string(call, prefix + "Id"), name = string(call, prefix + "Name"), zone = string(call, prefix + "Zone");
        if (!id.matches("[0-9A-Za-z_-]{1,32}") || name.isBlank() || name.length() > 80)
            throw new WeatherFailure("FIXED_CITY_INVALID");
        try {
            ZoneId requestedZone = ZoneId.of(zone);
            CityResolverPort.City verified = cities.resolveId(id, request);
            if (!requestedZone.equals(verified.zone())) throw new WeatherFailure("FIXED_CITY_ZONE_MISMATCH");
            if (!name.equals(verified.name())) throw new WeatherFailure("FIXED_CITY_NAME_MISMATCH");
            return new CityResolverPort.City(verified.id(), verified.name(), verified.district(), verified.zone(), 0, source);
        } catch (WeatherFailure known) { throw known; }
        catch (RuntimeException invalid) { throw new WeatherFailure("FIXED_CITY_INVALID"); }
    }
    private Map<String, Object> today(AgentRequest request, ToolCall call) throws WeatherFailure, InterruptedException {
        String id = string(call, "cityId"), name = string(call, "cityName");
        if (!id.matches("[0-9A-Za-z_-]{1,32}") || name.isBlank() || name.length() > 80) throw new WeatherFailure("CITY_ID_INVALID");
        try {
            ZoneId zone = ZoneId.of(string(call, "zoneId"));
            LocalDate date = LocalDate.parse(string(call, "localDate"));
            if (Math.abs(date.toEpochDay() - LocalDate.now(zone).toEpochDay()) > 1) throw new WeatherFailure("WEATHER_DATE_INVALID");
            return weather.today(new CityResolverPort.City(id, name, "", zone, 0, "FROZEN_STEP"), date, request);
        } catch (WeatherFailure known) { throw known; }
        catch (RuntimeException invalid) { throw new WeatherFailure("WEATHER_ARGUMENT_INVALID"); }
    }
    private static String string(ToolCall call, String key) { Object value = call.argument(key); return value instanceof String text ? text : ""; }
    private static long elapsed(long started) { return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }
}
