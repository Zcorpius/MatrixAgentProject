package com.matrix.agent.platform.weather;

import com.matrix.agent.identity.AgentRequest;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;

/** Bounded HTTPS transport: caller selects only known paths; URL and credential never enter tool output. */
public final class QWeatherTransport implements WeatherHttpPort, WeatherCityLookupPort {
    private static final int MAX_BYTES = 512 * 1024;
    private final OkHttpClient client;
    private final WeatherConfigStore configStore;
    public QWeatherTransport(OkHttpClient client, WeatherConfigStore configStore) {
        if (client.followRedirects() || client.followSslRedirects()) throw new IllegalArgumentException("weather redirects forbidden");
        this.client = client; this.configStore = configStore;
    }
    @Override public JSONObject get(String path, String query, AgentRequest request) throws WeatherFailure, InterruptedException {
        return execute(path, query, request, true);
    }
    /** Foreground Host picker only; the endpoint is fixed here, never supplied by a model. */
    @Override public JSONObject searchCity(String encodedQuery, AgentRequest request) throws WeatherFailure, InterruptedException {
        if (!encodedQuery.matches("location=[A-Za-z0-9%+._-]{1,180}&number=5&lang=zh"))
            throw new WeatherFailure("CITY_QUERY_INVALID");
        return execute("/geo/v2/city/lookup", encodedQuery, request, false);
    }
    private JSONObject execute(String path, String query, AgentRequest request, boolean automatic)
            throws WeatherFailure, InterruptedException {
        WeatherConfigStore.Config config = configStore.load();
        if (config == null) throw new WeatherFailure("WEATHER_NOT_CONFIGURED");
        authorize(request, automatic);
        HttpUrl url = HttpUrl.parse("https://" + config.host() + path + (query.isEmpty() ? "" : "?" + query));
        if (url == null || !url.isHttps() || !url.host().equals(config.host())) throw new WeatherFailure("WEATHER_ENDPOINT_INVALID");
        Request http = new Request.Builder().url(url).get().header("Accept", "application/json")
                .header(config.headerName(), config.headerValue(System.currentTimeMillis() / 1000)).build();
        Call call = client.newCall(http);
        call.timeout().timeout(Math.max(1, Math.min(16_000, request.remainingMillis())), TimeUnit.MILLISECONDS);
        Runnable abort = call::cancel;
        request.getCancellationToken().registerAbortHook(abort);
        try {
            authorize(request, automatic);
            try (Response response = call.execute()) {
                if (response.code() == 401 || response.code() == 403) throw new WeatherFailure("WEATHER_CREDENTIAL_REJECTED");
                if (response.code() == 429) throw new WeatherFailure("WEATHER_RATE_LIMITED");
                if (!response.isSuccessful() || response.isRedirect() || response.body() == null || !response.request().url().equals(url))
                    throw new WeatherFailure("WEATHER_HTTP_UNAVAILABLE");
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (InputStream stream = response.body().byteStream()) {
                    byte[] buffer = new byte[8192];
                    for (int count; (count = stream.read(buffer)) != -1;) {
                        authorize(request, automatic);
                        if (bytes.size() > MAX_BYTES - count) throw new WeatherFailure("WEATHER_RESPONSE_TOO_LARGE");
                        bytes.write(buffer, 0, count);
                    }
                }
                authorize(request, automatic);
                return new JSONObject(new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (WeatherFailure known) { throw known; }
        catch (java.io.InterruptedIOException timeout) { throw new WeatherFailure(request.isCancelled() ? "WEATHER_CANCELLED" : "WEATHER_TIMEOUT"); }
        catch (java.io.IOException offline) { throw new WeatherFailure("WEATHER_NETWORK_UNAVAILABLE"); }
        catch (org.json.JSONException malformed) { throw new WeatherFailure("WEATHER_RESPONSE_INVALID"); }
        finally { request.getCancellationToken().removeAbortHook(abort); call.cancel(); }
    }
    private static void authorize(AgentRequest request, boolean automatic) throws WeatherFailure {
        if (request.isCancelled()) throw new WeatherFailure("WEATHER_CANCELLED");
        if (request.remainingMillis() <= 0) throw new WeatherFailure("WEATHER_TIMEOUT");
        if (!automatic) return;
        var scope = request.getExecutionScope();
        if (!scope.automatic() || !scope.networkAllowed() || !scope.rejection().isEmpty()) throw new WeatherFailure("WEATHER_AUTHORIZATION_REVOKED");
    }
}
