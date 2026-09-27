package com.matrix.agent.launcher.data;

import static com.matrix.agent.api.handoff.HandoffProtocol.*;

import android.os.SystemClock;
import androidx.lifecycle.Observer;
import com.matrix.agent.api.common.ConnectionState;
import com.matrix.agent.api.handoff.*;
import com.matrix.agent.client.HandoffManager;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/** Each actual SDK connection gets a fresh Stub; the REUSE callback never waits for main. */
public final class HandoffClient implements AutoCloseable {
    public record Decision(int result, int reason) {
        public static Decision of(int result) { return new Decision(result, REASON_NONE); }
    }
    public interface Presentation {
        /** Reads an immutable snapshot; any scheduled bookkeeping must not wait for main. Null requests slow preparation. */
        Decision fastDecision(ExternalAppHandoffRequest request);
        void prepare(ExternalAppHandoffRequest request, Consumer<Decision> reply);
        void acknowledged(ExternalAppHandoffRequest request, boolean accepted);
        void launchFinished(String requestId, int result, long elapsed);
        void activity(ExternalUiActivitySnapshot snapshot);
        void connectionChanged(boolean connected);
    }
    private final LauncherHostGateway gateway;
    private final ExecutorService calls;
    private final Presentation presentation;
    private final Observer<Integer> observer = this::connectionChanged;
    private volatile Session session;
    private boolean connected;
    private boolean closed;

    private final class Session {
        volatile HandoffManager manager;
        final IExternalAppHandoffCallback callback = new IExternalAppHandoffCallback.Stub() {
            @Override public void onHandoffRequested(ExternalAppHandoffRequest request) {
                if (!valid() || !validRequest(request)) return;
                Decision fast = presentation.fastDecision(request);
                if (fast != null) { acknowledge(request, fast, false); return; }
                gateway.dispatchToMain(() -> {
                    if (valid() && beforeDeadline(request)) {
                        presentation.prepare(request, reply -> acknowledge(request, reply, true));
                    }
                });
            }
            @Override public void onLaunchAttemptFinished(String id, int result, long elapsed) {
                gateway.dispatchToMain(() -> { if (valid()) presentation.launchFinished(id, result, elapsed); });
            }
            @Override public void onExternalUiActivityChanged(ExternalUiActivitySnapshot value) {
                gateway.dispatchToMain(() -> { if (valid()) presentation.activity(value); });
            }
        };
        boolean valid() { return session == this && gateway.isConnected(); }
        void acknowledge(ExternalAppHandoffRequest request, Decision decision, boolean temporary) {
            boolean tracksPreparation = temporary || decision.result() == OVERLAY_PREPARED;
            try {
                calls.execute(() -> {
                    int receipt = EXPIRED;
                    if (valid() && beforeDeadline(request) && manager != null) {
                        receipt = manager.acknowledge(callback, request.handoffRequestId(),
                                decision.result(), decision.reason());
                        // One query-style retry, same identity and original deadline. Never resend media work.
                        if (receipt == STALE_REGISTRATION && valid() && beforeDeadline(request)) {
                            receipt = manager.acknowledge(callback, request.handoffRequestId(),
                                    decision.result(), decision.reason());
                        }
                    }
                    boolean accepted = receipt == ACCEPTED && valid();
                    if (tracksPreparation) gateway.dispatchToMain(() -> presentation.acknowledged(request, accepted));
                });
            } catch (RejectedExecutionException unavailable) {
                if (tracksPreparation) gateway.dispatchToMain(() -> presentation.acknowledged(request, false));
            }
        }
    }

    public HandoffClient(LauncherHostGateway gateway, LauncherExecutorRegistry executors,
            Presentation presentation) {
        this.gateway = gateway; calls = executors.handoffCalls(); this.presentation = presentation;
        gateway.connectionState().observeForever(observer);
    }
    private void connectionChanged(int state) {
        if (closed) return;
        boolean nowConnected = state == ConnectionState.CONNECTED;
        if (connected == nowConnected) return;
        connected = nowConnected;
        Session previous = session;
        session = null;
        presentation.connectionChanged(nowConnected);
        if (previous != null) unregister(previous);
        if (!nowConnected) return;
        Session replacement = new Session();
        session = replacement;
        gateway.executeVia(calls, agent -> {
            if (!replacement.valid()) return false;
            replacement.manager = agent.getHandoffManager();
            if (replacement.manager == null) return false;
            int result = replacement.manager.registerCallback(replacement.callback);
            if (!replacement.valid()) replacement.manager.unregisterCallback(replacement.callback);
            return result == ACCEPTED;
        }, result -> {
            if (session == replacement && (!result.isSuccess() || !Boolean.TRUE.equals(result.value))) {
                session = null;
                presentation.connectionChanged(false);
            }
        });
    }
    private void unregister(Session old) {
        try { calls.execute(() -> {
            if (old.manager != null) old.manager.unregisterCallback(old.callback);
        }); } catch (RejectedExecutionException ignored) { }
    }
    private static boolean beforeDeadline(ExternalAppHandoffRequest request) {
        return SystemClock.elapsedRealtime() < request.deadlineElapsedRealtimeMs();
    }
    private static boolean validRequest(ExternalAppHandoffRequest r) {
        return r != null && r.handoffRequestId() != null && r.runtimeRequestId() != null
                && r.conversationId() != null && r.conversationTaskId() != null
                && r.hostUserMessageId() != null && r.hostUserSequence() > 0
                && (r.reason() == LAUNCH_ACTIVITY || r.reason() == INTERACT_EXISTING_APP)
                && (r.preparationMode() == REUSE || r.preparationMode() == CREATE_OR_REBIND)
                && r.operationDeadlineElapsedRealtimeMs() >= r.deadlineElapsedRealtimeMs()
                && beforeDeadline(r);
    }
    @Override public void close() {
        closed = true; gateway.connectionState().removeObserver(observer);
        Session old = session; session = null;
        if (old != null) unregister(old);
    }
}
