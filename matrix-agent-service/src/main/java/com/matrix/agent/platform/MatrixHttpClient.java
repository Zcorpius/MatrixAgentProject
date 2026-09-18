package com.matrix.agent.platform;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;

/**
 * Process-owned HTTP client family for every MatrixAgent network boundary.
 *
 * <p>The clients deliberately share OkHttp's connection pool and dispatcher, while each use case
 * has an explicit timeout/redirect policy. Feature code receives a client from this class instead
 * of allocating transports or using {@code HttpURLConnection} directly.</p>
 */
public final class MatrixHttpClient {
    private final OkHttpClient provider;
    private final OkHttpClient metadata;
    private final OkHttpClient download;

    public MatrixHttpClient() {
        provider = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .callTimeout(105, TimeUnit.SECONDS)
                .build();
        // Catalog endpoints are pinned by their callers to a specific HTTPS host. Redirects
        // would invalidate that assertion, so reject them rather than silently following one.
        metadata = provider.newBuilder()
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(45, TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
        // Large model archives are range-resumed by the caller. There is no whole-call timeout,
        // but a stalled socket remains bounded by readTimeout and cancellation calls Call.cancel.
        download = provider.newBuilder()
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
                .build();
    }

    public OkHttpClient provider() { return provider; }
    public OkHttpClient metadata() { return metadata; }
    public OkHttpClient download() { return download; }
}
