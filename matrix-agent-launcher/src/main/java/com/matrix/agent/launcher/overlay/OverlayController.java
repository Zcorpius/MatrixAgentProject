package com.matrix.agent.launcher.overlay;

import static com.matrix.agent.api.handoff.HandoffProtocol.*;

import com.matrix.agent.diagnostics.HandoffDiagnostics;
import static com.matrix.agent.diagnostics.HandoffDiagnostics.Stage.*;
import android.app.AppOpsManager;
import android.app.KeyguardManager;
import android.content.*;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import com.matrix.agent.api.conversation.ConversationMessage;
import com.matrix.agent.api.conversation.ConversationSubmission;
import com.matrix.agent.api.handoff.*;
import com.matrix.agent.launcher.LauncherActivity;
import com.matrix.agent.launcher.data.*;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Application-owned presentation transaction and window lifetime; never holds an Activity. */
public final class OverlayController implements HandoffClient.Presentation, OverlayWindow.Actions, AutoCloseable {
    private record Snapshot(OverlayBinding binding, String visibleConversation, boolean visible,
            boolean prepared, boolean attached, long version, Set<String> dismissed) {}
    private record SendAttempt(OverlayBinding binding, OverlayDraftStore.Draft draft, String operationId) {}
    private final HandoffDiagnostics diagnostics;
    private final Context context;
    private final LauncherHostGateway gateway;
    private final com.matrix.agent.launcher.data.OverlayConversationSource repository;
    private final OverlayDraftStore drafts = new OverlayDraftStore();
    private final Set<String> dismissed = new LinkedHashSet<>();
    private final Set<String> anchorQueries = new HashSet<>();
    private final TreeMap<Long, OverlayBinding> queuedRounds = new TreeMap<>();
    private volatile Snapshot snapshot = new Snapshot(null, null, false, false, false, 0, Set.of());
    private OverlayConversationPresenter presenter;
    private OverlayWindow window;
    private LauncherHostGateway.ConnectionLease lease;
    private Preparation preparing;
    private RevealTicket ticket;
    private boolean ticketAccepted;
    // Visibility also encodes same-page suppression; it is not a lifetime discriminator.
    private boolean committedAsPrepared;
    private ScheduledFuture<?> revealExpiry;
    private String visibleConversation;
    private String returningConversation;
    private long bindingVersion;
    private int activity = -1;
    private long activityGeneration = -1;
    private boolean connected;
    private SendAttempt inFlightSend;
    private SendAttempt retry;
    private final AppOpsManager.OnOpChangedListener permissionChanged;
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { clear(false); }
    };

    private final class Preparation {
        final ExternalAppHandoffRequest request;
        final long expectedVersion;
        final Consumer<HandoffClient.Decision> reply;
        final LauncherHostGateway.ConnectionLease lease = gateway.acquireConnection();
        OverlayConversationPresenter presenter;
        OverlayWindow window;
        OverlayWindow suppressedPrevious;
        ScheduledFuture<?> expiry;
        int result;
        boolean replied;
        int launchResult;
        long dispatchTime;
        Preparation(ExternalAppHandoffRequest request, Consumer<HandoffClient.Decision> reply) {
            this.request = request; this.reply = reply; expectedVersion = bindingVersion;
        }
        boolean valid() {
            return preparing == this && connected && expectedVersion == bindingVersion
                    && SystemClock.elapsedRealtime() < request.deadlineElapsedRealtimeMs();
        }
        void respond(int result, int reason) {
            if (replied) return;
            replied = true; this.result = result;
            // Host may dispatch the next tool before the ACK receipt returns to main. Publish
            // the already-mounted candidate now; a subsequent authenticated REUSE can inspect it.
            publish();
            reply.accept(new HandoffClient.Decision(result, reason));
        }
        void dispose() {
            if (expiry != null) expiry.cancel(false);
            if (presenter != null) presenter.close();
            if (window != null) window.close();
            lease.close();
        }
    }

    public OverlayController(Context context, LauncherHostGateway gateway,
            com.matrix.agent.launcher.data.OverlayConversationSource repository) {
        this(context, gateway, repository, HandoffDiagnostics.NONE);
    }
    public OverlayController(Context context, LauncherHostGateway gateway,
            OverlayConversationSource repository, HandoffDiagnostics diagnostics) {
        this.diagnostics = diagnostics;
        this.context = context.getApplicationContext(); this.gateway = gateway; this.repository = repository;
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_BACKGROUND);
        androidx.core.content.ContextCompat.registerReceiver(context, screenReceiver, filter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
        permissionChanged = (op, pkg) -> gateway.dispatchToMain(() -> {
            if (!Settings.canDrawOverlays(context)) clear(false);
        });
        context.getSystemService(AppOpsManager.class).startWatchingMode(
                AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, context.getPackageName(), permissionChanged);
    }
    public OverlayDraftStore drafts() { return drafts; }
    public void conversationPageVisible(String id) {
        visibleConversation = id;
        if (presenter != null && Objects.equals(id, presenter.binding().conversationId())) {
            if (window != null) window.setHidden(true);
            if (Objects.equals(returningConversation, id)) {
                returningConversation = null; clear(false); return;
            }
        }
        revealIfReady(); publish();
    }
    public void configurationChanged() { if (window != null) window.update(); }

    @Override public HandoffClient.Decision fastDecision(ExternalAppHandoffRequest request) {
        Snapshot current = snapshot;
        if (current.dismissed().contains(request.runtimeRequestId())) return HandoffClient.Decision.of(USER_DISMISSED);
        boolean samePage = Objects.equals(current.visibleConversation(), request.conversationId());
        if (request.preparationMode() != REUSE) return null;
        if (current.binding() == null || !current.binding().sameRound(OverlayBinding.from(request))) {
            return new HandoffClient.Decision(OVERLAY_UNAVAILABLE, REUSE_STATE_STALE);
        }
        if (samePage && request.reason() == INTERACT_EXISTING_APP) return HandoffClient.Decision.of(PRESENTED_IN_LAUNCHER);
        if (current.visible()) return HandoffClient.Decision.of(OVERLAY_READY);
        if (samePage && request.reason() == LAUNCH_ACTIVITY && current.attached() && !current.prepared()) {
            // No window mutation or subscription on the fast path. Only arm a one-shot ticket
            // for the already attached hidden window; ACK and departure still gate its reveal.
            gateway.dispatchToMain(() -> {
                if (bindingVersion != current.version() || window == null || window.visible()
                        || presenter == null || !presenter.binding().sameRound(current.binding())
                        || !connected || SystemClock.elapsedRealtime() >= request.deadlineElapsedRealtimeMs()) return;
                ticket = new RevealTicket(request.handoffRequestId(), bindingVersion, request.operationDeadlineElapsedRealtimeMs());
                ticketAccepted = false; scheduleRevealExpiry(); publish();
            });
            return HandoffClient.Decision.of(OVERLAY_PREPARED);
        }
        return new HandoffClient.Decision(OVERLAY_UNAVAILABLE, REUSE_STATE_STALE);
    }

    @Override public void prepare(ExternalAppHandoffRequest request, Consumer<HandoffClient.Decision> reply) {
        if (!connected || SystemClock.elapsedRealtime() >= request.deadlineElapsedRealtimeMs()) return;
        if (dismissed.contains(request.runtimeRequestId())) { reply.accept(HandoffClient.Decision.of(USER_DISMISSED)); return; }
        if (preparing != null) { reply.accept(new HandoffClient.Decision(OVERLAY_UNAVAILABLE, OWNER_STATE_UNAVAILABLE)); return; }
        boolean samePage = Objects.equals(visibleConversation, request.conversationId());
        if (!(samePage && request.reason() == INTERACT_EXISTING_APP) && !canDisplay()) {
            reply.accept(new HandoffClient.Decision(OVERLAY_UNAVAILABLE,
                    Settings.canDrawOverlays(context) ? DEVICE_LOCKED : PERMISSION_DENIED)); return;
        }
        OverlayBinding incoming = OverlayBinding.from(request);
        // A CREATE request after a stale reuse can reuse the actual panel without replacing an editor.
        if (presenter != null && presenter.binding().sameRound(incoming) && window != null
                && window.visible()) {
            presenter.authenticateRuntime(incoming); publish(); reply.accept(HandoffClient.Decision.of(OVERLAY_READY)); return;
        }
        Preparation candidate = new Preparation(request, reply); preparing = candidate;
        candidate.expiry = gateway.schedule(() -> gateway.dispatchToMain(() -> expire(candidate)),
                Math.max(0, request.deadlineElapsedRealtimeMs() - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS);
        if (presenter == null || presenter.binding().sameRound(incoming) || presenter.terminal()) {
            subscribe(candidate); return;
        }
        // One RPC in flight for this anchor. Timeout cannot cancel a Binder transaction or spawn another.
        OverlayConversationPresenter old = presenter;
        String key = old.binding().conversationId() + ":" + old.binding().userSequence();
        if (!anchorQueries.add(key)) { reject(candidate, OWNER_STATE_UNAVAILABLE); return; }
        repository.messagesAround(old.binding().conversationId(), old.binding().userSequence(), result -> {
            anchorQueries.remove(key);
            if (!candidate.valid() || presenter != old) return;
            ConversationMessage anchor = result.isSuccess() && result.value != null && result.value.anchorExists
                    ? result.value.messages.stream().filter(m -> old.binding().userMessageId().equals(m.messageId)).findFirst().orElse(null)
                    : null;
            if (anchor == null) { reject(candidate, OWNER_STATE_UNAVAILABLE); return; }
            old.merge(anchor);
            if (old.terminal()) subscribe(candidate);
            else {
                candidate.respond(OVERLAY_BUSY, REASON_NONE); discardPreparation(candidate);
            }
        });
    }
    private void subscribe(Preparation candidate) {
        if (!candidate.valid()) { expire(candidate); return; }
        candidate.presenter = new OverlayConversationPresenter(repository, OverlayBinding.from(candidate.request),
                () -> { if (presenter == candidate.presenter) render(); });
        if (presenter != null) candidate.presenter.inheritConversation(presenter);
        candidate.presenter.start(ready -> {
            if (!candidate.valid()) { expire(candidate); return; }
            if (!ready) { reject(candidate, CONNECTION_LOST); return; }
            boolean samePage = Objects.equals(visibleConversation, candidate.request.conversationId());
            if (samePage && candidate.request.reason() == INTERACT_EXISTING_APP) {
                candidate.respond(PRESENTED_IN_LAUNCHER, REASON_NONE); return;
            }
            if (!canDisplay()) { reject(candidate, PERMISSION_DENIED); return; }
            long attachStarted = SystemClock.elapsedRealtime();
            boolean attachRecorded = false;
            try {
                candidate.window = new OverlayWindow(context, this, duration ->
                        recordWindow(candidate, FIRST_DRAW, true, duration));
                candidate.window.setInteractive(false);
                candidate.window.render(candidate.presenter.state(), drafts.get(candidate.request.conversationId()).text(), activity, false);
                if (!samePage && window != null && window.visible()) {
                    // Replacement is a presentation transaction too: never draw two entries.
                    // Retain the old window and data so an unaccepted ACK can roll back.
                    candidate.suppressedPrevious = window;
                    window.setHidden(true);
                }
                if (!candidate.valid()) { expire(candidate); return; }
                candidate.window.attach(samePage);
                recordWindow(candidate, WINDOW_ATTACH, true, SystemClock.elapsedRealtime() - attachStarted);
                attachRecorded = true;
                if (!candidate.valid()) { expire(candidate); return; }
                candidate.respond(samePage ? OVERLAY_PREPARED : OVERLAY_READY, REASON_NONE);
            } catch (RuntimeException failure) {
                if (!attachRecorded) recordWindow(candidate, WINDOW_ATTACH, false,
                        SystemClock.elapsedRealtime() - attachStarted);
                Log.w("MatrixOverlay", "window preparation failed", failure);
                reject(candidate, WINDOW_FAILED);
            }
        });
    }
    private void recordWindow(Preparation candidate, HandoffDiagnostics.Stage stage,
            boolean success, long duration) {
        diagnostics.record(stage, candidate.request.preparationMode(), success ? 1 : 0,
                success ? REASON_NONE : WINDOW_FAILED, success, SystemClock.elapsedRealtime(), duration,
                candidate.request.conversationTaskId(), candidate.request.handoffRequestId());
    }
    private void reject(Preparation candidate, int reason) {
        if (preparing != candidate) return;
        candidate.respond(OVERLAY_UNAVAILABLE, reason); discardPreparation(candidate);
    }
    private void expire(Preparation candidate) {
        if (preparing != candidate) return;
        candidate.respond(OVERLAY_UNAVAILABLE, OWNER_STATE_UNAVAILABLE); discardPreparation(candidate);
    }
    private void discardPreparation(Preparation candidate) {
        if (preparing == candidate) preparing = null;
        candidate.dispose();
        if (candidate.suppressedPrevious != null && candidate.suppressedPrevious == window
                && candidate.expectedVersion == bindingVersion && presenter != null && connected
                && !Objects.equals(visibleConversation, presenter.binding().conversationId()) && canDisplay()) {
            window.setHidden(false);
        }
        publish();
    }
    @Override public void acknowledged(ExternalAppHandoffRequest request, boolean accepted) {
        Preparation candidate = preparing;
        if (candidate == null || !candidate.request.handoffRequestId().equals(request.handoffRequestId())) {
            if (ticket != null && ticket.matches(request.handoffRequestId(), bindingVersion)) {
                if (!accepted) clearPreparedWindow();
                else { ticketAccepted = true; revealIfReady(); }
            }
            return;
        }
        if (!accepted || !candidate.valid() || !isPresentationReady(candidate.result)) {
            discardPreparation(candidate); return;
        }
        preparing = null;
        candidate.expiry.cancel(false);
        releaseCurrent();
        candidate.suppressedPrevious = null;
        presenter = candidate.presenter; window = candidate.window; lease = candidate.lease;
        committedAsPrepared = candidate.result == OVERLAY_PREPARED;
        bindingVersion++;
        queuedRounds.entrySet().removeIf(e -> e.getValue().sameRound(presenter.binding()));
        if (candidate.result == OVERLAY_PREPARED) {
            ticket = new RevealTicket(request.handoffRequestId(), bindingVersion, request.operationDeadlineElapsedRealtimeMs());
            ticketAccepted = true;
            scheduleRevealExpiry();
            if (candidate.launchResult != 0) launchFinished(request.handoffRequestId(), candidate.launchResult, candidate.dispatchTime);
        }
        render(); publish();
        if (window != null) window.setInteractive(true);
        Log.i("MatrixOverlay", "handoff committed result=" + candidate.result);
    }
    @Override public void launchFinished(String requestId, int result, long elapsed) {
        if (preparing != null && preparing.request.handoffRequestId().equals(requestId)) {
            preparing.launchResult = result; preparing.dispatchTime = elapsed; return;
        }
        if (ticket == null || !ticket.matches(requestId, bindingVersion)) return;
        if (result != DISPATCHED) {
            if (!committedAsPrepared && presenter != null && result == DISPATCH_FAILED) {
                presenter.notice("应用未能打开，请返回 Agent 查看");
            }
            clearPreparedWindow(); render(); return;
        }
        ticket.dispatched(elapsed); scheduleRevealExpiry(); revealIfReady();
    }
    private void revealIfReady() {
        if (ticket == null || presenter == null || window == null) return;
        long now = SystemClock.elapsedRealtime();
        if (ticket.expired(now)) { clearPreparedWindow(); return; }
        if (ticketAccepted && ticket.canReveal(now, Objects.equals(visibleConversation, presenter.binding().conversationId()))) {
            if (!canDisplay()) { clear(false); return; }
            ticket = null; cancelRevealExpiry(); committedAsPrepared = false;
            window.setHidden(false); publish();
            Log.i("MatrixOverlay", "prepared window revealed");
        }
    }
    private void scheduleRevealExpiry() {
        cancelRevealExpiry();
        RevealTicket expected = ticket;
        if (expected == null) return;
        revealExpiry = gateway.schedule(() -> gateway.dispatchToMain(() -> {
            if (ticket == expected && expected.expired(SystemClock.elapsedRealtime())) clearPreparedWindow();
        }), Math.max(0, expected.deadline() - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS);
    }
    private void cancelRevealExpiry() { if (revealExpiry != null) revealExpiry.cancel(false); revealExpiry = null; }
    private void clearPreparedWindow() {
        ticket = null; cancelRevealExpiry();
        // Only a never-revealed preparation belongs to this ticket. A suppressed committed
        // owner survives failed reuse, rejected ACKs and expiry with its subscription intact.
        if (committedAsPrepared) { releaseCurrent(); bindingVersion++; }
        publish();
    }
    @Override public void activity(ExternalUiActivitySnapshot value) {
        if (value == null || value.displayId() != 0 || value.generation() <= activityGeneration) return;
        activityGeneration = value.generation();
        activity = value.state() == IDLE || value.state() == AUTOMATION ? value.state() : -1;
        render();
    }
    @Override public void connectionChanged(boolean value) {
        connected = value; activity = -1; activityGeneration = -1;
        if (preparing != null) discardPreparation(preparing);
        clearPreparedWindow();
        if (presenter != null) presenter.setConnected(value);
        render(); publish();
    }
    @Override public void draftChanged(String text) {
        if (presenter == null) return;
        drafts.set(presenter.binding().conversationId(), text); render();
    }
    @Override public void send() {
        if (presenter == null || isSending() || !connected) return;
        OverlayBinding owner = presenter.binding();
        OverlayDraftStore.Draft draft = drafts.get(owner.conversationId());
        if (draft.text().isBlank()) return;
        SendAttempt attempt = retry != null && retry.binding().sameRound(owner)
                && retry.draft().text().equals(draft.text()) ? retry
                : new SendAttempt(owner, draft, UUID.randomUUID().toString());
        retry = attempt; inFlightSend = attempt;
        if (window != null) window.finishEditing();
        render();
        repository.submitTextOrAppend(owner.conversationId(), attempt.draft().text(), List.of(), null,
                attempt.operationId(), result -> {
            if (inFlightSend == attempt) inFlightSend = null;
            var receipt = result.value;
            if (!result.isSuccess() || receipt == null || !receipt.isAccepted()) {
                if (presenter != null && presenter.binding().sameRound(owner)) presenter.notice("发送尚未确认，草稿已保留；再次发送将核对同一次提交");
                render(); return;
            }
            drafts.clearIfRevision(owner.conversationId(), attempt.draft().revision());
            if (retry == attempt) retry = null;
            if (presenter == null || !presenter.binding().sameRound(owner)) return;
            if (receipt.outcome == ConversationSubmission.OUTCOME_PRIMARY_ACCEPTED) {
                queuedRounds.put(receipt.sequenceNo, new OverlayBinding(null, receipt.conversationId,
                        receipt.conversationTaskId, receipt.userMessageId, receipt.sequenceNo));
                presenter.notice("新一轮已受理"); advanceQueuedRound();
            } else if (receipt.outcome == ConversationSubmission.OUTCOME_STEER_ACCEPTED) {
                presenter.notice("已提交并入");
            } else presenter.notice("补充输入已受理，请返回会话查看投递状态");
            render();
        });
    }
    private void advanceQueuedRound() {
        if (presenter == null || !presenter.terminal() || queuedRounds.isEmpty() || preparing != null) return;
        OverlayBinding next = queuedRounds.firstEntry().getValue();
        if (!presenter.binding().conversationId().equals(next.conversationId())) return;
        OverlayConversationPresenter old = presenter;
        OverlayConversationPresenter candidate = new OverlayConversationPresenter(repository, next, this::render);
        candidate.inheritConversation(old);
        long version = bindingVersion;
        // Remove before subscribing to avoid recursive notifications scheduling duplicates.
        queuedRounds.pollFirstEntry();
        candidate.start(ready -> {
            if (!ready || presenter != old || version != bindingVersion) { candidate.close(); return; }
            presenter = candidate; old.close(); bindingVersion++; render(); publish();
        });
    }
    @Override public void loadOlderMessages() { if (presenter != null) presenter.loadOlder(); }
    @Override public void cancel() { if (presenter != null) presenter.cancel(); }
    @Override public void returnToAgent() {
        if (presenter == null) return;
        OverlayBinding binding = presenter.binding();
        returningConversation = binding.conversationId();
        Intent intent = new Intent(context, LauncherActivity.class).setAction(ACTION_OPEN_CONVERSATION)
                .putExtra(EXTRA_CONVERSATION_ID, binding.conversationId()).putExtra(EXTRA_MESSAGE_ID, binding.userMessageId())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try { context.startActivity(intent); }
        catch (RuntimeException failure) { returningConversation = null; presenter.notice("暂时无法返回，请从桌面打开 Agent"); }
    }
    @Override public void windowFailed(RuntimeException failure) {
        Log.w("MatrixOverlay", "window update failed", failure); clear(false);
    }
    @Override public void dismiss() { clear(true); }
    @Override public void changed() { publish(); }
    private boolean isSending() {
        return inFlightSend != null && presenter != null
                && inFlightSend.binding().sameRound(presenter.binding());
    }
    private void render() {
        if (presenter != null && window != null) {
            window.render(presenter.state(), drafts.get(presenter.binding().conversationId()).text(), activity, isSending());
        }
        advanceQueuedRound(); publish();
    }
    private void publish() {
        Preparation candidate = preparing;
        if (candidate != null && candidate.valid() && isPresentationReady(candidate.result)) {
            snapshot = new Snapshot(OverlayBinding.from(candidate.request), visibleConversation,
                    candidate.window != null && candidate.window.visible(),
                    candidate.result == OVERLAY_PREPARED, candidate.window != null && candidate.window.attached(),
                    candidate.expectedVersion, Set.copyOf(dismissed));
            return;
        }
        snapshot = new Snapshot(presenter == null ? null : presenter.binding(), visibleConversation,
                window != null && window.visible(), ticket != null, window != null && window.attached(),
                bindingVersion, Set.copyOf(dismissed));
    }
    private boolean canDisplay() {
        var guard = context.getSystemService(KeyguardManager.class);
        return Settings.canDrawOverlays(context) && (guard == null || !guard.isKeyguardLocked());
    }
    private void clear(boolean userDismissed) {
        Log.i("MatrixOverlay", "clear userDismissed=" + userDismissed);
        if (userDismissed && presenter != null && presenter.binding().runtimeId() != null) {
            dismissed.add(presenter.binding().runtimeId());
            if (dismissed.size() > 64) dismissed.remove(dismissed.iterator().next());
        }
        if (preparing != null) discardPreparation(preparing);
        releaseCurrent(); bindingVersion++; queuedRounds.clear(); publish();
    }
    private void releaseCurrent() {
        committedAsPrepared = false;
        ticket = null; cancelRevealExpiry();
        if (window != null) window.close(); window = null;
        if (presenter != null) presenter.close(); presenter = null;
        if (lease != null) lease.close(); lease = null;
    }
    @Override public void close() {
        clear(false); context.unregisterReceiver(screenReceiver);
        context.getSystemService(AppOpsManager.class).stopWatchingMode(permissionChanged);
    }
}
