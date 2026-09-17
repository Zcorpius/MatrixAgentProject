package com.matrix.agent.model;

import android.util.Log;

import com.matrix.agent.task.SafeLog;
import com.matrix.agent.task.identity.CancellationToken;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 最小 JSON-over-HTTP 传输边界。
 *
 * <p>协议适配器只提供 endpoint、JSON body 和认证头；连接生命周期、取消、脱敏日志及网络错误
 * 分类只在此处维护。这样新增 Provider 不会再把 transport 细节带回 {@link ModelApiClient}。</p>
 */
final class JsonHttpTransport {
    private static final String TAG = "MatrixAgent";
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 90_000;

    JSONObject post(String endpoint, JSONObject body, String header1, String value1,
            String header2, String value2, CancellationToken token) throws Exception {
        long started = System.nanoTime();
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        boolean hasAuth = (header1 != null && value1 != null && !value1.isEmpty())
                || (header2 != null && value2 != null && !value2.isEmpty());
        Log.d(TAG, "[Http] POST " + maskEndpoint(endpoint)
                + " payloadBytes=" + payload.length + " auth=" + (hasAuth ? "yes" : "no")
                + " abortable=" + (token != null));
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        Runnable abortHook = token == null ? null : connection::disconnect;
        if (abortHook != null) token.registerAbortHook(abortHook);
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (header1 != null && value1 != null && !value1.isEmpty()) {
                connection.setRequestProperty(header1, value1);
            }
            if (header2 != null && value2 != null && !value2.isEmpty()) {
                connection.setRequestProperty(header2, value2);
            }
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }
            if (Thread.currentThread().isInterrupted() || (token != null && token.isCancelled())) {
                Log.w(TAG, "[Http] worker thread interrupted before response");
                throw new InterruptedException("模型请求已取消");
            }
            int code = connection.getResponseCode();
            InputStream input = code >= 200 && code < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String response = readAll(input);
            Log.d(TAG, "[Http] <- HTTP " + code + " respBytes=" + response.length()
                    + " costMs=" + ((System.nanoTime() - started) / 1_000_000L));
            if (code < 200 || code >= 300) throwForHttpStatus(code, endpoint, response);
            return new JSONObject(response);
        } catch (java.net.SocketTimeoutException timeout) {
            throw new ModelApiException.TimeoutException(maskEndpoint(endpoint), timeout);
        } catch (java.io.IOException io) {
            throw new ModelApiException.NetworkException(maskEndpoint(endpoint), io);
        } finally {
            if (abortHook != null) token.removeAbortHook(abortHook);
            connection.disconnect();
        }
    }

    private static void throwForHttpStatus(int code, String endpoint, String response) {
        SafeLog.wProviderRaw(TAG, "[Http] HTTP error ", code, truncate(response, 500));
        String masked = maskEndpoint(endpoint);
        RuntimeException error = new IllegalStateException("HTTP " + code + ": body="
                + SafeLog.PROVIDER_RAW_PLACEHOLDER);
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

    private static String readAll(InputStream input) throws Exception {
        if (input == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
