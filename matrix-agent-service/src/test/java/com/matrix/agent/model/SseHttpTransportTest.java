package com.matrix.agent.model;

import com.matrix.agent.contract.ModelApiException;
import com.matrix.agent.identity.CancellationToken;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.*;
import org.json.JSONObject;
import org.junit.Test;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public final class SseHttpTransportTest {
    @Test public void deliversBeforeCompletionAndCancellationAbortsWithoutRetry() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody("data: first\n\n" + "data: later\n\n".repeat(100)).throttleBody(13, 200, TimeUnit.MILLISECONDS));
            server.start();
            var client = new OkHttpClient();
            var token = new CancellationToken();
            CountDownLatch first = new CountDownLatch(1);
            AtomicInteger events = new AtomicInteger();
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<?> future = worker.submit(() -> {
                    try { new SseHttpTransport(client).post(server.url("/stream").toString(), new JSONObject(), Map.of(),
                            token, System.currentTimeMillis()+5000, value -> { events.incrementAndGet(); first.countDown(); return false; }); }
                    catch (Exception expected) { throw new CompletionException(expected); }
                });
                assertTrue(first.await(2, TimeUnit.SECONDS));
                assertFalse(future.isDone());
                token.cancel();
                ExecutionException failure = assertThrows(ExecutionException.class, () -> future.get(1, TimeUnit.SECONDS));
                assertTrue(failure.getCause().getCause() instanceof InterruptedException);
                int afterCancel = events.get();
                assertEquals(1, server.getRequestCount());
                assertEquals(0, token.abortHookCount());
                assertEquals(afterCancel, events.get());
            } finally { worker.shutdownNow(); client.connectionPool().evictAll(); client.dispatcher().executorService().shutdownNow(); }
        }
    }
    @Test public void ongoingFragmentsCannotExtendAbsoluteDeadline() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody("data: x\n\n".repeat(100)).throttleBody(9, 50, TimeUnit.MILLISECONDS));
            server.start();
            var client = new OkHttpClient();
            var token = new CancellationToken();
            AtomicInteger events = new AtomicInteger();
            long began = System.nanoTime();
            try {
                assertThrows(ModelApiException.TimeoutException.class, () -> new SseHttpTransport(client).post(
                        server.url("/").toString(), new JSONObject(), Map.of(), token,
                        System.currentTimeMillis()+300, event -> { events.incrementAndGet(); return false; }));
                assertTrue(events.get() > 0);
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began) < 2000);
                assertEquals(1, server.getRequestCount());
                assertEquals(0, token.abortHookCount());
            } finally { client.connectionPool().evictAll(); client.dispatcher().executorService().shutdownNow(); }
        }
    }
    @Test public void eofAfterVisibleContentIsIncompleteAndDoesNotRetry() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: first\n\n"));
            server.start();
            var client = new OkHttpClient();
            AtomicInteger events = new AtomicInteger();
            try {
                assertThrows(java.io.EOFException.class, () -> new SseHttpTransport(client).post(server.url("/").toString(),
                        new JSONObject(), Map.of(), new CancellationToken(), System.currentTimeMillis()+2000,
                        event -> { events.incrementAndGet(); return false; }));
                assertEquals(1, events.get()); assertEquals(1, server.getRequestCount());
            } finally { client.connectionPool().evictAll(); client.dispatcher().executorService().shutdownNow(); }
        }
    }
}
