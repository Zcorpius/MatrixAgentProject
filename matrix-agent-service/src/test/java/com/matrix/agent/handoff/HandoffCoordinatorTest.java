package com.matrix.agent.handoff;

import static com.matrix.agent.api.handoff.HandoffProtocol.*;
import static org.junit.Assert.*;

import com.matrix.agent.api.handoff.*;
import com.matrix.agent.identity.CancellationToken;
import com.matrix.agent.platform.media.*;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class HandoffCoordinatorTest {
    private final AtomicLong clock = new AtomicLong(100);
    private final HandoffContextRegistry contexts = new HandoffContextRegistry();
    private final List<Integer> fallbacks = new CopyOnWriteArrayList<>();
    private final List<Integer> results = new CopyOnWriteArrayList<>();
    private final List<ExternalAppHandoffRequest> requests = new CopyOnWriteArrayList<>();
    private final com.matrix.agent.diagnostics.HandoffDiagnostics diagnostics =
            new com.matrix.agent.diagnostics.HandoffDiagnostics();
    private final HandoffCoordinator coordinator = new HandoffCoordinator(contexts, clock::get,
            (binding, result) -> fallbacks.add(result), (mode, result, reason, elapsed) -> results.add(result), diagnostics);
    private final AtomicInteger response = new AtomicInteger(OVERLAY_READY);
    private final HandoffCoordinator.Endpoint endpoint = new HandoffCoordinator.Endpoint() {
        @Override public void request(ExternalAppHandoffRequest request) {
            requests.add(request);
            if (response.get() != 0) coordinator.acknowledge("launcher", 10001, request.handoffRequestId(), response.get(), REASON_NONE);
        }
        @Override public void launchFinished(String id, int result, long elapsed) { }
        @Override public void activity(ExternalUiActivitySnapshot snapshot) { }
    };
    private void bind() {
        contexts.bind(new HandoffContextRegistry.Binding("runtime", "conversation", "task", "user", 5, "driver", "DRIVER"));
    }
    private void register() {
        coordinator.register(new HandoffCoordinator.Registration("launcher", 10001, 0, "driver", "DRIVER", endpoint));
    }
    private LaunchContext context(String operation) { return new LaunchContext("runtime", operation, 10_000, new CancellationToken()); }
    @Test public void unboundLaneSkipsPresentation() throws Exception {
        register(); assertEquals(RESULT_PRESENTATION_SKIPPED, coordinator.prepareOutcome(context("one"), MediaApp.QQMUSIC, LAUNCH_ACTIVITY));
        assertTrue(requests.isEmpty()); assertTrue(fallbacks.isEmpty());
    }
    @Test public void diagnosticsSeparatePresentationFromIntentOutcomeAndCachedReuse() throws Exception {
        bind(); register();
        var operation = context("one");
        coordinator.prepare(operation, MediaApp.QQMUSIC, LAUNCH_ACTIVITY);
        coordinator.prepare(operation, MediaApp.QQMUSIC, LAUNCH_ACTIVITY);
        coordinator.launchFinished(operation, DISPATCH_FAILED);
        var events = diagnostics.snapshot().events();
        assertEquals(2, events.size());
        assertEquals(com.matrix.agent.diagnostics.HandoffDiagnostics.Stage.HANDOFF, events.get(0).stage());
        assertTrue(events.get(0).success());
        assertEquals(com.matrix.agent.diagnostics.HandoffDiagnostics.Stage.INTENT_DISPATCH, events.get(1).stage());
        assertFalse(events.get(1).success());
        assertEquals(events.get(0).task(), events.get(1).task());
        assertEquals(events.get(0).request(), events.get(1).request());
    }
    @Test public void noClientFallsBackImmediately() throws Exception {
        bind(); coordinator.prepare(context("one"), MediaApp.QQMUSIC, LAUNCH_ACTIVITY);
        assertEquals(List.of(LAUNCHER_NOT_CONNECTED), fallbacks);
    }
    @Test public void activeOwnerScopeMustMatch() throws Exception {
        bind(); coordinator.register(new HandoffCoordinator.Registration("launcher", 10001, 0, "passenger", "PASSENGER", endpoint));
        coordinator.prepare(context("one"), MediaApp.QQMUSIC, LAUNCH_ACTIVITY);
        assertTrue(requests.isEmpty()); assertEquals(List.of(LAUNCHER_NOT_CONNECTED), fallbacks);
    }
    @Test public void initialThenReuseHasFiftyMillisecondBudget() throws Exception {
        bind(); register();
        var first = context("first");
        try (var ignored = coordinator.begin(first)) { coordinator.prepare(first, MediaApp.QQMUSIC, LAUNCH_ACTIVITY); }
        var second = context("second");
        try (var ignored = coordinator.begin(second)) { coordinator.prepare(second, MediaApp.QQMUSIC, INTERACT_EXISTING_APP); }
        assertEquals(CREATE_OR_REBIND, requests.get(0).preparationMode());
        assertEquals(1200, requests.get(0).deadlineElapsedRealtimeMs() - 100);
        assertEquals(REUSE, requests.get(1).preparationMode());
        assertEquals(50, requests.get(1).deadlineElapsedRealtimeMs() - 100);
    }
    @Test public void internalFallbackUsesOneReadyPreparation() throws Exception {
        bind(); register(); var context = context("one");
        coordinator.prepare(context, MediaApp.QQMUSIC, INTERACT_EXISTING_APP);
        coordinator.prepare(context, MediaApp.QQMUSIC, LAUNCH_ACTIVITY);
        assertEquals(1, requests.size());
    }
    @Test public void pageOnlyUpgradeUsesOriginalDeadline() throws Exception {
        bind(); register(); response.set(PRESENTED_IN_LAUNCHER); var context = context("one");
        coordinator.prepare(context, MediaApp.QQMUSIC, INTERACT_EXISTING_APP);
        clock.set(900); response.set(OVERLAY_PREPARED);
        coordinator.prepare(context, MediaApp.QQMUSIC, LAUNCH_ACTIVITY);
        assertEquals(2, requests.size());
        assertEquals(requests.get(0).deadlineElapsedRealtimeMs(), requests.get(1).deadlineElapsedRealtimeMs());
    }
    @Test public void duplicateAckReturnsOriginalReceiptWithoutDispatch() throws Exception {
        bind(); register(); coordinator.prepare(context("one"), MediaApp.QQMUSIC, LAUNCH_ACTIVITY);
        clock.set(9000);
        assertEquals(ACCEPTED, coordinator.acknowledge("launcher", 10001, requests.get(0).handoffRequestId(), OVERLAY_READY, REASON_NONE));
        assertEquals(STALE_REGISTRATION, coordinator.acknowledge("launcher", 9, requests.get(0).handoffRequestId(), OVERLAY_READY, REASON_NONE));
        assertEquals(1, requests.size());
    }
    @Test public void replacementInvalidatesPendingAndOldAck() throws Exception {
        bind(); register(); response.set(0);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var done = executor.submit(() -> { coordinator.prepare(context("one"), MediaApp.QQMUSIC, LAUNCH_ACTIVITY); return true; });
            awaitRequestCount(1);
            coordinator.register(new HandoffCoordinator.Registration("replacement", 10001, 0, "driver", "DRIVER", endpoint));
            assertTrue(done.get(1, TimeUnit.SECONDS));
            assertEquals(STALE_REGISTRATION, coordinator.acknowledge("launcher", 10001, requests.get(0).handoffRequestId(), OVERLAY_READY, 0));
            assertEquals(EXPIRED, coordinator.acknowledge("replacement", 10001, requests.get(0).handoffRequestId(), OVERLAY_READY, 0));
        } finally { executor.shutdownNow(); }
    }
    @Test public void cancellationWakesWorkerAndStopsAction() throws Exception {
        bind(); register(); response.set(0); var context = context("one");
        var executor = Executors.newSingleThreadExecutor();
        try {
            var done = executor.submit(() -> {
                try { coordinator.prepare(context, MediaApp.QQMUSIC, LAUNCH_ACTIVITY); return "unexpected"; }
                catch (MediaPlatformException expected) { return expected.code(); }
            });
            awaitRequestCount(1); context.cancellation().cancel();
            assertEquals("EXECUTION_CANCELLED", done.get(1, TimeUnit.SECONDS));
            assertEquals(0, context.cancellation().abortHookCount());
        } finally { executor.shutdownNow(); }
    }
    @Test public void reuseTimeoutNeverRetriesSlowOrClosesExistingEntry() throws Exception {
        bind(); register(); coordinator.prepare(context("first"), MediaApp.QQMUSIC, LAUNCH_ACTIVITY);
        response.set(0); long start = System.nanoTime();
        coordinator.prepare(context("second"), MediaApp.QQMUSIC, INTERACT_EXISTING_APP);
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 600);
        assertEquals(2, requests.size()); assertEquals(HANDOFF_TIMED_OUT, (int) results.get(1));
        assertTrue(fallbacks.isEmpty());
    }
    @Test public void deadlineAckCannotWinAfterDeadline() throws Exception {
        bind(); register(); response.set(0); var executor = Executors.newSingleThreadExecutor();
        try {
            var done = executor.submit(() -> { coordinator.prepare(context("one"), MediaApp.QQMUSIC, LAUNCH_ACTIVITY); return true; });
            awaitRequestCount(1); clock.set(requests.get(0).deadlineElapsedRealtimeMs());
            assertEquals(EXPIRED, coordinator.acknowledge("launcher", 10001, requests.get(0).handoffRequestId(), OVERLAY_READY, 0));
            assertTrue(done.get(1, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
    }
    private void awaitRequestCount(int count) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (requests.size() < count && System.nanoTime() < until) Thread.sleep(1);
        assertEquals(count, requests.size());
    }
}
