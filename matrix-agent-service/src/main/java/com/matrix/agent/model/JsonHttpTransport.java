package com.matrix.agent.model;

import com.matrix.agent.contract.ModelApiException;

import android.util.Log;

import com.matrix.agent.identity.CancellationToken;
import com.matrix.agent.platform.MatrixHttpClient;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 最小 JSON-over-HTTP 传输边界。
 *
 * <p>协议适配器只提供 endpoint、JSON body 和认证头；连接生命周期、取消、脱敏日志及网络错误
 * 分类只在此处维护。这样新增 Provider 不会再把 transport 细节带回 {@link ModelApiClient}。</p>
 */
final class JsonHttpTransport {
    private static final String TAG = "MatrixAgent";
    private static final String PROVIDER_RAW_PLACEHOLDER = "[provider-raw-redacted]";
    /** Tool-calling JSON is small; never let a hostile/misconfigured provider allocate unbounded heap. */
    static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient client;

    JsonHttpTransport(OkHttpClient client) {
        if (client == null) throw new IllegalArgumentException("client 不能为空");
        this.client = client;
    }

    JSONObject post(String endpoint, JSONObject body, String header1, String value1,
            String header2, String value2, CancellationToken token) throws Exception {
        long started = System.nanoTime();
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        boolean hasAuth = (header1 != null && value1 != null && !value1.isEmpty())
                || (header2 != null && value2 != null && !value2.isEmpty());
        Log.d(TAG, "[Http] POST " + maskEndpoint(endpoint)
                + " payloadBytes=" + payload.length + " auth=" + (hasAuth ? "yes" : "no")
                + " abortable=" + (token != null));
        Request.Builder request = new Request.Builder().url(endpoint)
                .post(RequestBody.create(payload, JSON))
                .header("Content-Type", "application/json; charset=utf-8");
        if (header1 != null && value1 != null && !value1.isEmpty()) request.header(header1, value1);
        if (header2 != null && value2 != null && !value2.isEmpty()) request.header(header2, value2);
        Call call = client.newCall(request.build());
        Runnable abortHook = token == null ? null : call::cancel;
        if (abortHook != null) token.registerAbortHook(abortHook);
        try {
            if (Thread.currentThread().isInterrupted() || (token != null && token.isCancelled())) {
                Log.w(TAG, "[Http] worker thread interrupted before response");
                throw new InterruptedException("模型请求已取消");
            }
            try (Response result = call.execute()) {
                int code = result.code();
                String response = readAll(result.body() == null ? null : result.body().byteStream(),
                        MAX_RESPONSE_BYTES, maskEndpoint(endpoint));
            Log.d(TAG, "[Http] <- HTTP " + code + " respBytes=" + response.length()
                    + " costMs=" + ((System.nanoTime() - started) / 1_000_000L));
            if (code < 200 || code >= 300) throwForHttpStatus(code, endpoint, response);
            return new JSONObject(response);
            }
        } catch (java.net.SocketTimeoutException timeout) {
            throw new ModelApiException.TimeoutException(maskEndpoint(endpoint), timeout);
        } catch (java.io.IOException io) {
            throw new ModelApiException.NetworkException(maskEndpoint(endpoint), io);
        } finally {
            if (abortHook != null) token.removeAbortHook(abortHook);
            call.cancel();
        }
    }

    private static void throwForHttpStatus(int code, String endpoint, String response) {
        // Provider responses can contain user data and credentials; raw text never reaches logs.
        Log.w(TAG, "[Http] HTTP error code=" + code + " body=" + PROVIDER_RAW_PLACEHOLDER
                + " bytes=" + (response == null ? 0 : response.length()));
        String masked = maskEndpoint(endpoint);
        RuntimeException error = new IllegalStateException("HTTP " + code + ": body="
                + PROVIDER_RAW_PLACEHOLDER);
        if (code == 429) throw new ModelApiException.RateLimitException(masked, error);
        if (code >= 500) throw new ModelApiException.ServerException(code, masked, error);
        if (code >= 400) throw new ModelApiException.ClientException(code, masked, error);
        throw error;
    }

    private static String maskEndpoint(String endpoint) {
        if (endpoint == null) return "";
        int query = endpoint.indexOf('?');
        return query >= 0 ? endpoint.substring(0, query) : endpoint;
    }

    /**
     * Reads a provider response with an explicit byte cap.  {@code Content-Length} cannot be
     * trusted or may be absent (chunked responses), so the streamed byte count is authoritative.
     */
    private static String readAll(InputStream input, int maxBytes, String sanitizedEndpoint)
            throws Exception {
        if (input == null) return "";
        try (InputStream source = input;
                ByteArrayOutputStream result = new ByteArrayOutputStream(Math.min(maxBytes, 8192))) {
            byte[] buffer = new byte[8192];
            for (int read; (read = source.read(buffer)) != -1;) {
                if (result.size() > maxBytes - read) {
                    throw new ModelApiException.ResponseTooLargeException(sanitizedEndpoint,
                            new IllegalStateException("response exceeds " + maxBytes + " bytes"));
                }
                result.write(buffer, 0, read);
            }
            return result.toString(StandardCharsets.UTF_8.name());
        }
    }

}
