package com.matrix.agent.handoff;

import static com.matrix.agent.api.handoff.HandoffProtocol.*;

import com.matrix.agent.api.common.ParcelSchema;
import com.matrix.agent.diagnostics.HandoffDiagnostics;
import static com.matrix.agent.diagnostics.HandoffDiagnostics.Stage.*;
import com.matrix.agent.api.handoff.ExternalAppHandoffRequest;
import com.matrix.agent.api.handoff.ExternalUiActivitySnapshot;
import com.matrix.agent.platform.media.ExternalAppHandoffPort;
import com.matrix.agent.platform.media.LaunchContext;
import com.matrix.agent.platform.media.MediaApp;
import com.matrix.agent.platform.media.MediaPlatformException;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/**
 * Bounded, connection-scoped handoff state machine. No remote call occurs under its monitor.
 * Only the tool worker waits; ACK, cancellation and death resolve the same pending object.
 */
public final class HandoffCoordinator implements ExternalAppHandoffPort {
    public interface Endpoint {
        void request(ExternalAppHandoffRequest request) throws Exception;
        void launchFinished(String requestId, int result, long elapsed) throws Exception;
        void activity(ExternalUiActivitySnapshot snapshot) throws Exception;
    }
    public interface Fallback { void show(HandoffContextRegistry.Binding binding, int result); }
    public interface Metrics { void record(int mode, int result, int reason, long elapsedMs); }
    public record Registration(Object identity, int uid, int userId, String owner, String zone,
            Endpoint endpoint) {}
    private static final int HISTORY_LIMIT = 128;
    private record Resolution(int result, int reason, int receipt) {}
    private static final class Pending {
        final Registration registration;
        final ExternalAppHandoffRequest request;
        final CompletableFuture<Resolution> completion = new CompletableFuture<>();
        Resolution resolution;
        Pending(Registration registration, ExternalAppHandoffRequest request) {
            this.registration = registration; this.request = request;
        }
    }
    private record Preparation(Registration registration, ExternalAppHandoffRequest request,
            Resolution resolution) {}

    private final HandoffContextRegistry contexts;
    private final LongSupplier clock;
    private final Fallback fallback;
    private final Metrics metrics;
    private final HandoffDiagnostics diagnostics;
    private final ExternalUiActivityTracker activity = new ExternalUiActivityTracker(this::publishActivity);
    private final Map<String, Pending> pending = new HashMap<>();
    private final LinkedHashMap<String, Pending> history = new LinkedHashMap<>();
    private final Map<String, Preparation> operations = new HashMap<>();
    private final java.util.Set<String> activeScopes = new java.util.HashSet<>();
    private Registration registration;
    private String reusableRuntime;
    private int reusableResult;

    public HandoffCoordinator(HandoffContextRegistry contexts, LongSupplier clock,
            Fallback fallback, Metrics metrics) {
        this(contexts, clock, fallback, metrics, HandoffDiagnostics.NONE);
    }
    public HandoffCoordinator(HandoffContextRegistry contexts, LongSupplier clock,
            Fallback fallback, Metrics metrics, HandoffDiagnostics diagnostics) {
        this.contexts = contexts; this.clock = clock; this.fallback = fallback; this.metrics = metrics;
        this.diagnostics = diagnostics;
    }

    public void register(Registration value) {
        synchronized (this) {
            if (!sameRegistration(value, registration)) {
                invalidate();
                registration = value;
            }
        }
        try { value.endpoint().activity(activity.snapshot()); }
        catch (Exception failure) { unregister(value.identity(), value.uid()); }
    }
    public synchronized void unregister(Object identity, int uid) {
        if (!matches(registration, identity, uid)) return;
        invalidate(); registration = null;
    }
    public synchronized void clearRegistration() { invalidate(); registration = null; }
    private void invalidate() {
        for (Pending item : pending.values().toArray(new Pending[0])) {
            resolve(item, new Resolution(LAUNCHER_NOT_CONNECTED, CONNECTION_LOST, STALE_REGISTRATION));
        }
        reusableRuntime = null;
        operations.clear(); history.clear();
    }

    public synchronized int acknowledge(Object identity, int uid, String requestId,
            int result, int reason) {
        if (!matches(registration, identity, uid)) return STALE_REGISTRATION;
        Pending item = pending.get(requestId);
        if (item == null) item = history.get(requestId);
        if (item == null || !sameRegistration(item.registration, registration)) return EXPIRED;
        if (item.resolution != null) {
            return item.resolution.result() == result && item.resolution.reason() == reason
                    ? item.resolution.receipt() : ALREADY_RESOLVED;
        }
        if (clock.getAsLong() >= item.request.deadlineElapsedRealtimeMs()) {
            resolve(item, new Resolution(HANDOFF_TIMED_OUT, REASON_NONE, EXPIRED));
            return EXPIRED;
        }
        if (!isClientResult(result) || reason < REASON_NONE || reason > CONNECTION_LOST) {
            return ALREADY_RESOLVED;
        }
        resolve(item, new Resolution(result, reason, ACCEPTED));
        if (isPresentationReady(result) || result == USER_DISMISSED) {
            reusableRuntime = item.request.runtimeRequestId();
            reusableResult = result;
        } else if (reason == REUSE_STATE_STALE) reusableRuntime = null;
        return ACCEPTED;
    }

    /** Caller holds monitor. Completion continuations are never installed on this future. */
    private void resolve(Pending item, Resolution result) {
        if (item.resolution != null) return;
        item.resolution = result;
        pending.remove(item.request.handoffRequestId());
        history.put(item.request.handoffRequestId(), item);
        while (history.size() > HISTORY_LIMIT) history.remove(history.keySet().iterator().next());
        item.completion.complete(result);
    }

    @Override public Operation begin(LaunchContext context) {
        synchronized (this) {
            if (!activeScopes.add(context.operationId())) return () -> {};
        }
        Operation handle = activity.begin(context.operationId());
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        return () -> {
            if (!closed.compareAndSet(false, true)) return;
            synchronized (this) {
                activeScopes.remove(context.operationId());
                operations.remove(context.operationId());
            }
            handle.close();
        };
    }

    @Override public void prepare(LaunchContext context, MediaApp app, int reason)
            throws MediaPlatformException {
        prepareOutcome(context, app, reason);
    }

    /** Observable presentation result for diagnostics; no execution success is implied. */
    public int prepareOutcome(LaunchContext context, MediaApp app, int reason)
            throws MediaPlatformException {
        context.checkActive();
        HandoffContextRegistry.Binding binding = contexts.find(context.runtimeRequestId());
        if (binding == null) {
            diagnostics.record(HANDOFF, 0, RESULT_PRESENTATION_SKIPPED, REASON_NONE, false,
                    clock.getAsLong(), -1, null, context.operationId());
            return RESULT_PRESENTATION_SKIPPED;
        }
        final Pending item;
        final boolean hadEntry;
        synchronized (this) {
            Registration current = registration;
            if (current == null || !Objects.equals(current.owner(), binding.ownerUserId())
                    || !Objects.equals(current.zone(), binding.zone())) {
                item = null; hadEntry = false;
            } else {
                Preparation previous = operations.get(context.operationId());
                if (previous != null && sameRegistration(previous.registration(), current)) {
                    // Only page-only -> actual launch requires an upgrade, using the frozen deadline.
                    if (previous.resolution().result() != PRESENTED_IN_LAUNCHER
                            || reason != LAUNCH_ACTIVITY) return previous.resolution().result();
                }
                long now = clock.getAsLong();
                int mode = Objects.equals(reusableRuntime, context.runtimeRequestId())
                        ? REUSE : CREATE_OR_REBIND;
                if (reusableResult == PRESENTED_IN_LAUNCHER && reason == LAUNCH_ACTIVITY) mode = CREATE_OR_REBIND;
                // Page-only upgrade needs main-thread preparation but gains no new time budget.
                if (previous != null) mode = CREATE_OR_REBIND;
                long deadline = previous != null ? previous.request().deadlineElapsedRealtimeMs()
                        : Math.min(context.deadlineElapsedMillis(), now
                                + (mode == REUSE ? REUSE_TIMEOUT_MS : PREPARE_TIMEOUT_MS));
                var request = new ExternalAppHandoffRequest(ParcelSchema.CURRENT,
                        UUID.randomUUID().toString(), context.operationId(), context.runtimeRequestId(),
                        binding.conversationId(), binding.conversationTaskId(), binding.hostUserMessageId(),
                        binding.hostUserSequence(), app.packageName(), reason, mode, now, deadline,
                        context.deadlineElapsedMillis());
                item = new Pending(current, request);
                pending.put(request.handoffRequestId(), item);
                hadEntry = mode == REUSE;
            }
        }
        if (item == null) {
            diagnostics.record(HANDOFF, 0, LAUNCHER_NOT_CONNECTED, CONNECTION_LOST, false,
                    clock.getAsLong(), -1, binding.conversationTaskId(), context.operationId());
            fallback.show(binding, LAUNCHER_NOT_CONNECTED); return LAUNCHER_NOT_CONNECTED;
        }
        Runnable cancelled = () -> {
            synchronized (this) {
                resolve(item, new Resolution(EXECUTION_CANCELLED, REASON_NONE, EXPIRED));
            }
        };
        context.cancellation().registerAbortHook(cancelled);
        Resolution result;
        try {
            if (!item.completion.isDone() && clock.getAsLong() < item.request.deadlineElapsedRealtimeMs()) {
                try { item.registration.endpoint().request(item.request); }
                catch (Exception failure) { unregister(item.registration.identity(), item.registration.uid()); }
            }
            long remaining = Math.max(0, item.request.deadlineElapsedRealtimeMs() - clock.getAsLong());
            try { result = item.completion.get(remaining, TimeUnit.MILLISECONDS); }
            catch (TimeoutException timeout) {
                synchronized (this) {
                    resolve(item, new Resolution(HANDOFF_TIMED_OUT, REASON_NONE, EXPIRED));
                    result = item.resolution;
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); cancelled.run(); result = item.completion.join();
            } catch (ExecutionException impossible) { throw new IllegalStateException(impossible); }
        } finally { context.cancellation().removeAbortHook(cancelled); }
        synchronized (this) {
            if (sameRegistration(registration, item.registration)) {
                operations.put(context.operationId(), new Preparation(item.registration, item.request, result));
            }
        }
        diagnostics.record(HANDOFF, item.request.preparationMode(), result.result(), result.reason(),
                isPresentationReady(result.result()), clock.getAsLong(),
                clock.getAsLong() - item.request.createdElapsedRealtimeMs(),
                binding.conversationTaskId(), item.request.handoffRequestId());
        metrics.record(item.request.preparationMode(), result.result(), result.reason(),
                clock.getAsLong() - item.request.createdElapsedRealtimeMs());
        context.checkActive();
        if (!isPresentationReady(result.result()) && result.result() != USER_DISMISSED
                && !(hadEntry && result.result() == HANDOFF_TIMED_OUT)) {
            fallback.show(binding, result.result());
        }
        return result.result();
    }

    @Override public void launchFinished(LaunchContext context, int result) {
        Preparation value;
        synchronized (this) { value = operations.get(context.operationId()); }
        HandoffContextRegistry.Binding binding = contexts.find(context.runtimeRequestId());
        diagnostics.record(INTENT_DISPATCH, value == null ? 0 : value.request().preparationMode(),
                result, REASON_NONE, result == DISPATCHED, clock.getAsLong(), -1,
                binding == null ? null : binding.conversationTaskId(),
                value == null ? context.operationId() : value.request().handoffRequestId());
        if (value == null) return;
        try { value.registration().endpoint().launchFinished(value.request().handoffRequestId(),
                result, clock.getAsLong()); }
        catch (Exception failure) { unregister(value.registration().identity(), value.registration().uid()); }
    }
    private void publishActivity(ExternalUiActivitySnapshot snapshot) {
        Registration current;
        synchronized (this) { current = registration; }
        if (current == null) return;
        try { current.endpoint().activity(snapshot); }
        catch (Exception failure) { unregister(current.identity(), current.uid()); }
    }
    private static boolean sameRegistration(Registration a, Registration b) {
        return a != null && b != null && matches(a, b.identity(), b.uid());
    }
    private static boolean matches(Registration value, Object identity, int uid) {
        return value != null && value.uid() == uid && value.identity().equals(identity);
    }
}
