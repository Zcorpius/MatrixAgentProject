package com.matrix.agent.download;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Small, bounded HTTPS JSON transport for the two Host-owned model metadata endpoints. */
final class TrustedHttpsJson {
    private TrustedHttpsJson() { }

    static String get(String endpoint, String expectedHost, String userAgent, int maxBytes,
            OkHttpClient client) throws Exception {
        if (client == null) throw new IllegalArgumentException("http client 不能为空");
        URL url = requireEndpoint(endpoint, expectedHost);
        Request request = new Request.Builder().url(url)
                .header("Accept", "application/json")
                .header("User-Agent", userAgent)
                .get().build();
        try (Response response = client.newCall(request).execute()) {
            int status = response.code();
            if (status < 200 || status >= 300) {
                throw new java.io.IOException("metadata endpoint returned HTTP " + status);
            }
            long declaredLength = response.body() == null ? 0 : response.body().contentLength();
            if (declaredLength > maxBytes) {
                throw new java.io.IOException("metadata response exceeds limit");
            }
            if (response.body() == null) {
                throw new java.io.IOException("metadata response has no body");
            }
            try (InputStream input = response.body().byteStream();
                    ByteArrayOutputStream output = new ByteArrayOutputStream(
                            declaredLength < 0 ? 4096 : (int) declaredLength)) {
                byte[] buffer = new byte[4096];
                for (int count; (count = input.read(buffer)) != -1;) {
                    if (output.size() + count > maxBytes) {
                        throw new java.io.IOException("metadata response exceeds limit");
                    }
                    output.write(buffer, 0, count);
                }
                return output.toString(StandardCharsets.UTF_8.name());
            }
        }
    }

    static URL requireEndpoint(String value, String expectedHost) throws Exception {
        URL url = new URL(value);
        if (!"https".equalsIgnoreCase(url.getProtocol())
                || !expectedHost.equalsIgnoreCase(url.getHost())
                || url.getUserInfo() != null || url.getPort() != -1) {
            throw new IllegalArgumentException("untrusted metadata endpoint");
        }
        return url;
    }
}
