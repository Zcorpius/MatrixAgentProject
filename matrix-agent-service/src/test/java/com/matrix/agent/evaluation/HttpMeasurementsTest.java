package com.matrix.agent.evaluation;

import static org.junit.Assert.*;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.json.JSONObject;
import org.junit.Test;

public final class HttpMeasurementsTest {
    @Test public void measurementDoesNotConsumeResponseOrPersistCredentialsAndPrompts() throws Exception {
        HttpMeasurements measurements = new HttpMeasurements();
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(measurements).build();
        String response = "{\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3},\"choices\":[]}";
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody(response));
            Request request = new Request.Builder().url(server.url("/completion"))
                    .header("Authorization", "Bearer synthetic-test-secret")
                    .post(RequestBody.create(MediaType.get("application/json"),
                            "{\"temperature\":0.1,\"messages\":[{\"content\":\"private-test-prompt\"}]}"))
                    .build();
            try (var actual = client.newCall(request).execute()) { assertEquals(response, actual.body().string()); }
            JSONObject sample = measurements.snapshot().getJSONObject(0);
            assertEquals(12, sample.getJSONObject("usage").getInt("prompt_tokens"));
            assertEquals(0.1, sample.getDouble("temperature"), 0.00001);
            assertEquals("provider_default", sample.getString("seed"));
            assertFalse(sample.toString().contains("synthetic-test-secret"));
            assertFalse(sample.toString().contains("private-test-prompt"));
        } finally {
            client.dispatcher().executorService().shutdownNow();
            client.connectionPool().evictAll();
        }
    }

    @Test public void absentUsageRemainsUnknownRatherThanZero() throws Exception {
        HttpMeasurements measurements = new HttpMeasurements();
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(measurements).build();
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{}"));
            try (var response = client.newCall(new Request.Builder().url(server.url("/")).build()).execute()) {
                assertEquals("{}", response.body().string());
            }
            assertTrue(measurements.snapshot().getJSONObject(0).isNull("usage"));
        } finally {
            client.dispatcher().executorService().shutdownNow();
            client.connectionPool().evictAll();
        }
    }

    @Test public void resumedMeasurementsRetainTheCleanPrefixAndDetectRateLimits() throws Exception {
        HttpMeasurements measurements = new HttpMeasurements();
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(measurements).build();
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{}"));
            server.enqueue(new MockResponse().setResponseCode(429).setBody("{}"));
            for (int i = 0; i < 2; i++) {
                try (var response = client.newCall(new Request.Builder().url(server.url("/")).build()).execute()) {
                    assertEquals(i == 0 ? 200 : 429, response.code());
                }
            }
            assertEquals(2, measurements.count());
            assertFalse(measurements.hasStatusSince(0, 500));
            assertTrue(measurements.hasStatusSince(1, 429));
            HttpMeasurements resumed = new HttpMeasurements(measurements.snapshot());
            assertEquals(2, resumed.count());
            assertFalse(resumed.hasStatusSince(2, 429));
            assertTrue(resumed.hasStatusSince(0, 429));
        } finally {
            client.dispatcher().executorService().shutdownNow();
            client.connectionPool().evictAll();
        }
    }
}
