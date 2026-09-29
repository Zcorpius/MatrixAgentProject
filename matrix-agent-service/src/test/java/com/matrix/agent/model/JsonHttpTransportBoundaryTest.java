package com.matrix.agent.model;

import com.matrix.agent.contract.ModelApiException;

import static org.junit.Assert.fail;

import org.json.JSONObject;
import org.junit.Test;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

/** Regression coverage for the provider-response memory boundary. */
public final class JsonHttpTransportBoundaryTest {
    @Test public void completionDeadlineIncludesSlowResponseBody() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setBody("{\"choices\":[]}")
                    .setBodyDelay(2, java.util.concurrent.TimeUnit.SECONDS));
            var config = new com.matrix.agent.contract.ModelConfig("test", "test",
                    com.matrix.agent.contract.ApiProtocol.OPENAI_CHAT, server.url("/").toString(),
                    "test", "", false, com.matrix.agent.contract.PlannerMode.NATIVE_TOOL_CALLING);
            long started = System.nanoTime();
            org.junit.Assert.assertThrows(ModelApiException.TimeoutException.class, () ->
                    ModelApiClient.forTesting().complete(config, "system", "user",
                            new com.matrix.agent.identity.CancellationToken(), System.currentTimeMillis() + 200));
            org.junit.Assert.assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1500);
            org.junit.Assert.assertEquals(1, server.getRequestCount());
        }
    }

    @Test
    public void rejectsChunkedResponsePastByteLimit() throws Exception {
        MockWebServer server = new MockWebServer();
        server.start();
        try {
            // No Content-Length is supplied by chunking: the streaming counter, rather than a
            // header, must enforce the limit.
            server.enqueue(new MockResponse().setChunkedBody(
                    repeat('x', JsonHttpTransport.MAX_RESPONSE_BYTES + 1), 8_192));
            try {
                ModelApiClient.forTesting().post(server.url("/v1/chat").toString(), new JSONObject(),
                        null, null, null, null, null);
                fail("oversized provider response must be rejected");
            } catch (ModelApiException.ResponseTooLargeException expected) {
                // expected: response was not materialised into an unbounded StringBuilder.
            }
        } finally {
            server.shutdown();
        }
    }

    @Test
    public void acceptsAJsonResponseAtTheLimit() throws Exception {
        MockWebServer server = new MockWebServer();
        server.start();
        try {
            String prefix = "{\"result\":\"";
            String suffix = "\"}";
            int contentLength = JsonHttpTransport.MAX_RESPONSE_BYTES
                    - prefix.length() - suffix.length();
            server.enqueue(new MockResponse().setBody(prefix + repeat('x', contentLength) + suffix));
            ModelApiClient.forTesting().post(server.url("/v1/chat").toString(), new JSONObject(),
                    null, null, null, null, null);
        } finally {
            server.shutdown();
        }
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        java.util.Arrays.fill(chars, value);
        return new String(chars);
    }
}
