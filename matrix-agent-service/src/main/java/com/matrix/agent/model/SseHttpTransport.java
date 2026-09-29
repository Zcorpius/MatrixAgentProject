package com.matrix.agent.model;

import com.matrix.agent.identity.CancellationToken;
import com.matrix.agent.contract.ModelApiException;
import okhttp3.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Bounded SSE transport. A single absolute call timeout includes every read and callback. */
final class SseHttpTransport {
    static final int MAX_BYTES = 2 * 1024 * 1024;
    static final int MAX_EVENT_CHARS = 65_536;
    @FunctionalInterface interface EventConsumer { boolean accept(String data) throws Exception; }
    private final OkHttpClient client;
    SseHttpTransport(OkHttpClient client) { this.client = client; }

    void post(String endpoint, JSONObject body, Map<String, String> headers, CancellationToken token,
            long deadline, EventConsumer consumer) throws Exception {
        long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0) throw new ModelApiException.TimeoutException("stream", new java.util.concurrent.TimeoutException());
        Request.Builder request = new Request.Builder().url(endpoint)
                .post(RequestBody.create(body.toString(), MediaType.get("application/json; charset=utf-8")))
                .header("Accept", "text/event-stream");
        headers.forEach((key, value) -> { if (value != null && !value.isEmpty()) request.header(key, value); });
        Call call = client.newCall(request.build());
        call.timeout().timeout(remaining, TimeUnit.MILLISECONDS);
        Runnable abort = call::cancel;
        if (token != null) token.registerAbortHook(abort);
        try {
            if (token != null && token.isCancelled()) throw new InterruptedException("cancelled");
            try (Response response = call.execute()) {
                if (!response.isSuccessful()) {
                    int code = response.code();
                    var cause = new IllegalStateException("HTTP " + code);
                    if (code == 429) throw new ModelApiException.RateLimitException("stream", cause);
                    if (code >= 500) throw new ModelApiException.ServerException(code, "stream", cause);
                    throw new ModelApiException.ClientException(code, "stream", cause);
                }
                if (response.body() == null || !response.header("Content-Type", "").toLowerCase(java.util.Locale.ROOT)
                        .startsWith("text/event-stream")) throw new java.net.ProtocolException("SSE response required");
                read(response.body().byteStream(), token, consumer);
            }
        } catch (java.io.InterruptedIOException timeout) {
            if ((token != null && token.isCancelled()) || Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("stream cancelled");
            }
            throw new ModelApiException.TimeoutException("stream", timeout);
        } catch (java.io.EOFException | java.net.ProtocolException | java.nio.charset.CharacterCodingException invalidProtocol) {
            throw invalidProtocol;
        } catch (java.io.IOException network) {
            if (token != null && token.isCancelled()) throw new InterruptedException("stream cancelled");
            throw new ModelApiException.NetworkException("stream", network);
        } finally {
            if (token != null) token.removeAbortHook(abort);
            call.cancel();
        }
    }

    static void read(InputStream input, CancellationToken token, EventConsumer consumer) throws Exception {
        InputStream bounded = new FilterInputStream(input) {
            int count;
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                int n = super.read(bytes, offset, Math.min(length, MAX_BYTES - count + 1));
                if (n > 0 && (count += n) > MAX_BYTES) throw new java.net.ProtocolException("stream size exceeded");
                return n;
            }
            @Override public int read() throws IOException {
                int b = super.read();
                if (b >= 0 && ++count > MAX_BYTES) throw new java.net.ProtocolException("stream size exceeded");
                return b;
            }
        };
        var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (var reader = new BufferedReader(new InputStreamReader(bounded, decoder))) {
            StringBuilder data = new StringBuilder();
            boolean first = true;
            for (String line; (line = reader.readLine()) != null;) {
                if (token != null && token.isCancelled()) throw new InterruptedException("cancelled");
                if (first && line.startsWith("\uFEFF")) line = line.substring(1);
                first = false;
                if (line.length() > MAX_EVENT_CHARS) throw new java.net.ProtocolException("SSE line size exceeded");
                if (line.isEmpty()) {
                    if (data.length() > 0) {
                        data.setLength(data.length() - 1);
                        if (consumer.accept(data.toString())) return;
                        data.setLength(0);
                    }
                } else if (line.startsWith("data:")) {
                    String value = line.substring(line.startsWith("data: ") ? 6 : 5);
                    if (data.length() + value.length() + 1 > MAX_EVENT_CHARS) throw new java.net.ProtocolException("SSE event size exceeded");
                    data.append(value).append('\n');
                }
            }
            // A terminated TCP stream is not a model completion signal.
            throw new EOFException("provider stream ended without completion");
        }
    }
}
