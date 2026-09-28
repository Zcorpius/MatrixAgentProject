package com.matrix.agent.platform.web;

import com.matrix.agent.identity.AgentRequest;
import okhttp3.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** One bounded request, no redirects, credentials, arbitrary URL input or automatic application retries. */
public final class PinnedWebSearchTransport implements WebSearchProvider.Fetcher {
    public static final int MAX_BYTES=512*1024;
    private final OkHttpClient client;
    private final AtomicLong arxivSlot=new AtomicLong();
    public PinnedWebSearchTransport(OkHttpClient client) {
        if (client.followRedirects() || client.followSslRedirects()) throw new IllegalArgumentException("search must reject redirects");
        this.client=client;
    }
    @Override public String fetch(WebSearchSource source,String query,AgentRequest request) throws Exception {
        WebSearchProvider.authorize(request);
        if (source==WebSearchSource.ARXIV) awaitArxivSlot(request);
        HttpUrl endpoint=source.endpoint(query);
        if (!endpoint.isHttps() || !endpoint.host().equals(source.host)) throw new SecurityException("search endpoint");
        Request http=new Request.Builder().url(endpoint).header("Accept",source==WebSearchSource.ARXIV?"application/atom+xml":"application/json")
                .header("User-Agent","MatrixAgentResearch/1.0 (user-requested metadata research)").get().build();
        Call call=client.newCall(http);
        call.timeout().timeout(Math.max(1,Math.min(18_000,request.remainingMillis())),TimeUnit.MILLISECONDS);
        Runnable abort=call::cancel;
        request.getCancellationToken().registerAbortHook(abort);
        try {
            WebSearchProvider.authorize(request);
            try(Response response=call.execute()) {
                if (!response.isSuccessful() || response.isRedirect() || response.body()==null) throw new IOException("search HTTP response unavailable");
                if (!response.request().url().equals(endpoint)) throw new SecurityException("search redirect");
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                byte[] buffer=new byte[8192];
                try(InputStream stream=response.body().byteStream()) {
                    for(int count;(count=stream.read(buffer))!=-1;) {
                        WebSearchProvider.authorize(request);
                        if (bytes.size()>MAX_BYTES-count) throw new IOException("search response exceeds bound");
                        bytes.write(buffer,0,count);
                    }
                }
                WebSearchProvider.authorize(request);
                return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString();
            }
        } finally { request.getCancellationToken().removeAbortHook(abort); call.cancel(); }
    }
    private void awaitArxivSlot(AgentRequest request) throws InterruptedException, InterruptedIOException {
        long reserved;
        while(true) {
            long previous=arxivSlot.get();
            reserved=Math.max(System.nanoTime(),previous);
            if (arxivSlot.compareAndSet(previous,reserved+TimeUnit.SECONDS.toNanos(3))) break;
        }
        while(System.nanoTime()<reserved) {
            WebSearchProvider.authorize(request);
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            java.util.concurrent.locks.LockSupport.parkNanos(Math.min(TimeUnit.MILLISECONDS.toNanos(50),reserved-System.nanoTime()));
        }
    }
}
