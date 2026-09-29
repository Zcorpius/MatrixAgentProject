package com.matrix.agent.host.di;

import static com.matrix.agent.api.schedule.ScheduleCodes.*;
import com.matrix.agent.data.schedule.ScheduleRunEntity;
import com.matrix.agent.identity.*;
import com.matrix.agent.schedule.domain.ScheduleCodec;
import com.matrix.agent.schedule.execution.*;
import com.matrix.agent.schedule.store.ScheduleAdmissionStore;
import com.matrix.agent.task.scheduler.PreparedAutomaticTask;
import com.matrix.agent.task.tool.ToolResult;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Durable acceptance precedes execution. Process death never implies that a started action is safe to replay. */
public final class ScheduledExecutionGraph implements ScheduleExecutionRuntime {
    private final ScheduleGraph schedules;
    private final CalendarBindingGateway calendars;
    private final Supplier<AppContainer> container;
    private final ExecutorService workers = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(100), action -> new Thread(action, "matrix-scheduled-execution"), new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService controls = new com.matrix.agent.platform.BoundedScheduledExecutor("matrix-schedule-controls", 32);
    private final Semaphore modelSlot = new Semaphore(1, true);
    private final ScheduledSpeech speech = new ScheduledSpeech();
    private final WorkflowExecutionGraph workflows;
    private final ConcurrentHashMap<String, Active> active = new ConcurrentHashMap<>();
    private static final class Active {
        final CancellationToken token = new CancellationToken();
        volatile AutoCloseable cpu;
        final java.util.concurrent.CopyOnWriteArrayList<Runnable> completions = new java.util.concurrent.CopyOnWriteArrayList<>();
    }
    public ScheduledExecutionGraph(ScheduleGraph schedules, Supplier<AppContainer> container, CalendarBindingGateway calendars) {
        this.schedules = schedules; this.container = container; this.calendars = calendars;
        workflows = new WorkflowExecutionGraph(schedules, speech, modelSlot, calendars);
        controls.scheduleWithFixedDelay(this::checkCancellation, 250, 250, TimeUnit.MILLISECONDS);
    }
    @Override public void execute(String runId, Runnable completion) {
        try { com.matrix.agent.schedule.domain.ScheduleNormalizer.requireUuid(runId != null && runId.startsWith("calendar:") ? runId.substring(9) : runId); }
        catch (RuntimeException badId) { completion.run(); return; }
        Active candidate = new Active(); candidate.completions.add(completion);
        Active previous = active.putIfAbsent(runId, candidate);
        if (previous != null) {
            synchronized (previous) {
                if (active.get(runId) == previous) previous.completions.add(completion);
                else completion.run();
            }
            return;
        }
        try { workers.execute(() -> run(runId, candidate)); }
        catch (RejectedExecutionException rejected) { complete(runId, candidate); schedules.changed(); }
    }
    private void run(String id, Active execution) {
        boolean asynchronous = false;
        boolean modelAcquired = false;
        AutoCloseable arbitration = null;
        try {
            execution.cpu = schedules.holdExecutionCpu(120_000);
            if (id.startsWith("calendar:")) { calendars.reconcile(id.substring(9)); return; }
            ScheduleRunEntity run = schedules.call(store -> store.dao().run(id));
            if (run == null) return;
            if (!schedules.authorized(run)) {
                schedules.call(store -> { new ScheduleAdmissionStore(store).finish(run.runId, FAILED, DELIVERY_BLOCKED, "", "AUTHORIZATION_REVOKED", schedules.clock().sample(), false); return null; });
                return;
            }
            if (run.state == CANCEL_REQUESTED) {
                var receipt = schedules.call(store -> store.dao().acceptance(run.runtimeRequestId));
                schedules.call(store -> { new ScheduleAdmissionStore(store).finish(run.runId,
                        receipt != null && receipt.state == RUNNING ? EXECUTION_UNKNOWN : CANCELLED,
                        DELIVERY_NOT_REQUIRED, "", "CANCELLED_OR_INTERRUPTED", schedules.clock().sample(), false); return null; });
                return;
            }
            if (terminalRun(run.state)) {
                if (run.deliveryStatus == DELIVERY_PENDING) deliverResult(run, execution.token);
                return;
            }
            var spec = ScheduleCodec.spec(run.specJson);
            if (spec.action.kind == WORKFLOW) {
                var profile = com.matrix.agent.schedule.workflow.WorkflowCatalog.require(spec.action.templateId, spec.action.templateVersion).profile();
                if (profile == ExecutionProfile.RESEARCH) {
                    execution.cpu.close();
                    execution.cpu = schedules.holdExecutionCpu(Math.min(profile.maxActiveMillis(), Math.max(1, run.expiresAt - System.currentTimeMillis())), profile);
                }
            }
            if (spec.timing.kind == CALENDAR_OFFSET && !calendars.verifyRun(run)) {
                schedules.call(store -> { new ScheduleAdmissionStore(store).finish(run.runId, CANCELLED, DELIVERY_NOT_REQUIRED,
                        "", "CALENDAR_SOURCE_CHANGED", schedules.clock().sample(), false); return null; });
                return;
            }
            if (spec.action.kind == NOTIFICATION) {
                String blocked = schedules.notifications().post(run);
                schedules.call(store -> { new ScheduleAdmissionStore(store).recordDelivery(run.runId, "notification", blocked.isEmpty() ? "DELIVERED" : blocked,
                        schedules.notifications().policySnapshot(com.matrix.agent.schedule.android.ScheduleNotificationPort.REMINDERS), schedules.clock().sample());
                    new ScheduleAdmissionStore(store).finish(run.runId, blocked.isEmpty() ? SUCCEEDED : FAILED,
                        blocked.isEmpty() ? DELIVERED : DELIVERY_BLOCKED, "", blocked, schedules.clock().sample(), blocked.isEmpty()); return null; });
                return;
            }
            var authority = new RuntimeExecutionPort.AuthorizationSnapshot(run.authorizationJson, Actor.valueOf(run.actor), VehicleZone.parse(run.zone));
            var context = new RuntimeExecutionPort.ExecutionContext("", run.dataEpoch, run.expiresAt);
            var accepted = schedules.call(store -> new DurableRuntimeExecutionPort(store).accept(run.runtimeRequestId, id, spec, authority, context));
            if (terminalRun(accepted.state())) return;
            if (accepted.state() == RUNNING && spec.action.kind != WORKFLOW) {
                finish(run, EXECUTION_UNKNOWN, "", "PROCESS_INTERRUPTED_AFTER_START", 0);
                return;
            }
            // Lazily assembling the full execution graph is outside the reminder receiver and its deadline.
            AppContainer host = container.get();
            host.automaticTasks().validate(spec.action.capabilities);
            if (spec.action.kind == AGENT) {
                while (!(modelAcquired = modelSlot.tryAcquire(250, TimeUnit.MILLISECONDS))) {
                    if (execution.token.isCancelled() || System.currentTimeMillis() > run.expiresAt) {
                        finish(run, MISSED, "", "QUEUE_EXPIRED", 0); return;
                    }
                }
            }
            if (spec.action.kind == AGENT) {
                while ((arbitration = host.automaticTasks().tryModelLease(execution.token,
                        host.automaticTasks().readOnly(spec.action.capabilities))) == null) {
                    if (execution.token.isCancelled() || System.currentTimeMillis() > run.expiresAt) {
                        finish(run, MISSED, "", "QUEUE_EXPIRED", 0); return;
                    }
                    java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                }
            }
            boolean started = schedules.call(store -> store.database().runInTransaction(() -> {
                var current = store.dao().run(id); var receipt = store.dao().acceptance(run.runtimeRequestId);
                if (current == null || receipt == null || current.dataEpoch != store.currentEpoch()
                        || current.state == CANCEL_REQUESTED || !schedules.userReady() || System.currentTimeMillis() > current.expiresAt) return false;
                receipt.state = RUNNING;
                if (receipt.startedAt == null) receipt.startedAt = System.currentTimeMillis();
                store.dao().updateAcceptance(receipt);
                current.state = RUNNING; current.startedAt = receipt.startedAt;
                if (current.startedElapsed == null) current.startedElapsed = android.os.SystemClock.elapsedRealtime();
                store.dao().updateRun(current); return true;
            }));
            if (!started) { finish(run, CANCELLED, "", "ADMISSION_EXPIRED_OR_CANCELLED", 0); return; }
            if (spec.action.kind == WORKFLOW) {
                workflows.start(run, host, execution.token, (state, result, reason) -> {
                    try { finish(run, state, result, reason, 0); }
                    finally { complete(id, execution); schedules.changed(); }
                });
                asynchronous = true;
                return;
            }
            long began = android.os.SystemClock.elapsedRealtime();
            ExecutionScope scope = scope(run, execution.token, began, 60_000);
            PreparedAutomaticTask task = new PreparedAutomaticTask(run.runtimeRequestId, id, spec.action.text,
                    authority.actor(), authority.zone(), run.dataEpoch, 60_000,
                    host.automaticTasks().readOnly(spec.action.capabilities), scope);
            int state; String result; String reason;
            if (spec.action.kind == AGENT) {
                var outcome = host.automaticTasks().agent(task, execution.token);
                state = switch (outcome.getFinalState()) {
                    case SUCCEEDED -> SUCCEEDED; case PARTIALLY_SUCCEEDED -> PARTIAL;
                    case CANCELLED, PREEMPTED, DEFERRED -> CANCELLED;
                    case EXECUTION_UNKNOWN -> EXECUTION_UNKNOWN; default -> FAILED;
                };
                result = outcome.getFinalAssistantText() == null ? "" : bounded(outcome.getFinalAssistantText());
                reason = outcome.getStopReason().name();
            } else if (spec.action.kind == TOOL) {
                ToolResult outcome = host.automaticTasks().tool(task, spec.action.capabilities.get(0), parameters(spec.action.parametersJson), execution.token);
                state = outcome.getStatus() == ToolResult.Status.EXECUTION_UNKNOWN ? EXECUTION_UNKNOWN
                        : outcome.getStatus() == ToolResult.Status.SUCCESS && outcome.isVerified() ? SUCCEEDED : FAILED;
                result = bounded(outcome.getMessage()); reason = outcome.getStatus().name();
            } else {
                state = FAILED; result = ""; reason = "WORKFLOW_EXECUTOR_NOT_READY";
            }
            finish(run, state, result, reason, android.os.SystemClock.elapsedRealtime() - began);
        } catch (Exception failure) {
            try {
                var run = schedules.call(store -> store.dao().run(id));
                if (run != null) {
                    var receipt = schedules.call(store -> store.dao().acceptance(run.runtimeRequestId));
                    finish(run, receipt != null && receipt.state == RUNNING ? EXECUTION_UNKNOWN : FAILED,
                            "", "EXECUTION_UNAVAILABLE", 0);
                }
            } catch (RuntimeException unavailable) { /* Existing acceptance remains authoritative for recovery. */ }
        } finally {
            if (arbitration != null) try { arbitration.close(); } catch (Exception ignored) { }
            if (modelAcquired) modelSlot.release();
            if (!asynchronous) { complete(id, execution); schedules.changed(); }
        }
    }
    private ExecutionScope scope(ScheduleRunEntity run, CancellationToken token, long began, long limit) {
        var action = ScheduleCodec.spec(run.specJson).action;
        return ExecutionScope.automatic(Set.copyOf(action.capabilities), action.allowNetwork, new ExecutionScope.Guard() {
            @Override public String rejection() {
                if (token.isCancelled() || !schedules.authorized(run)) return "AUTHORIZATION_REVOKED";
                try { return schedules.call(store -> {
                    var current = store.dao().run(run.runId);
                    return current == null || current.dataEpoch != store.currentEpoch() || current.state == CANCEL_REQUESTED || current.reason.equals("USER_CANCELLED_DELIVERY")
                            ? "EXECUTION_REVOKED" : "";
                }); } catch (RuntimeException unavailable) { return "PERSISTENCE_UNAVAILABLE"; }
            }
            @Override public long remainingMillis() {
                long expiry = terminalRun(run.state) ? run.expiresAt + 600_000 : run.expiresAt;
                return Math.min(limit - (android.os.SystemClock.elapsedRealtime() - began), expiry - System.currentTimeMillis());
            }
            @Override public boolean prepareTool() { return calendars.verifyRun(run); }
            @Override public boolean reserveTool() {
                return schedules.call(store -> store.database().runInTransaction(() -> {
                    var current = store.dao().run(run.runId);
                    if (current == null || current.dataEpoch != store.currentEpoch() || current.toolCalls >= 16 || current.state != RUNNING) return false;
                    current.toolCalls++; store.dao().updateRun(current); return true;
                }));
            }
        });
    }
    private void finish(ScheduleRunEntity run, int state, String result, String reason, long activeMillis) {
        schedules.call(store -> {
            if (store.currentEpoch() != run.dataEpoch) return null;
            store.database().runInTransaction(() -> {
                var receipt = store.dao().acceptance(run.runtimeRequestId);
                var current = store.dao().run(run.runId);
                if (receipt == null || current == null || current.dataEpoch != run.dataEpoch || terminalRun(current.state)) return;
                receipt.state = state; receipt.result = result; receipt.reason = reason; receipt.completedAt = System.currentTimeMillis();
                store.dao().updateAcceptance(receipt); current.activeMillis += activeMillis; store.dao().updateRun(current);
                new ScheduleAdmissionStore(store).finish(run.runId, state, current.deliveryStatus == DELIVERY_PENDING ? DELIVERY_NOT_REQUIRED : current.deliveryStatus,
                        result, reason, schedules.clock().sample(), false);
                if (ScheduleCodec.spec(run.specJson).action.kind != WORKFLOW && state != CANCELLED) {
                    current = store.dao().run(run.runId); current.deliveryStatus = DELIVERY_PENDING; store.dao().updateRun(current);
                    var delivery = new com.matrix.agent.data.schedule.ScheduleOutboxEntity();
                    delivery.effectId = "deliver:" + run.runId; delivery.kind = "DELIVER_RESULT";
                    delivery.runId = run.runId; delivery.scheduleId = run.scheduleId; delivery.dataEpoch = run.dataEpoch;
                    delivery.nextAttemptAt = System.currentTimeMillis(); delivery.cutoffAt = delivery.nextAttemptAt + 600_000;
                    var clock = schedules.clock().sample(); delivery.bootId = clock.bootId(); delivery.nextElapsedAt = clock.elapsedMillis();
                    store.dao().putOutbox(delivery);
                }
            });
            return null;
        });
    }
    private void deliverResult(ScheduleRunEntity run, CancellationToken token) {
        String text = run.result.isEmpty() ? run.reason : run.result;
        String blocked = schedules.notifications().postResult(run, text);
        schedules.call(store -> { new ScheduleAdmissionStore(store).recordDelivery(run.runId, "notification", blocked.isEmpty() ? "DELIVERED" : blocked,
                schedules.notifications().policySnapshot(com.matrix.agent.schedule.android.ScheduleNotificationPort.resultChannel(run)), schedules.clock().sample()); return null; });
        String speechReason = ScheduleCodec.spec(run.specJson).action.speakResult && run.result.isEmpty()
                ? "NOT_ATTEMPTED_EMPTY_RESULT" : "";
        if (blocked.isEmpty() && ScheduleCodec.spec(run.specJson).action.speakResult && !run.result.isEmpty()) {
            boolean claimed = schedules.call(store -> store.database().runInTransaction(() -> {
                var current = store.dao().run(run.runId);
                if (current == null || current.dataEpoch != store.currentEpoch() || current.speechClaimed || current.deliveryStatus != DELIVERY_PENDING) return false;
                current.speechClaimed = true; store.dao().updateRun(current); return true;
            }));
            if (!claimed) speechReason = "SPEECH_OUTCOME_UNKNOWN";
            else speechReason = speech.speak(container.get(), run.runtimeRequestId, text,
                    scope(run, token, android.os.SystemClock.elapsedRealtime(), 15_000), 15_000);
        }
        String outcome = !blocked.isEmpty() ? blocked : speechReason;
        int state = run.state == SUCCEEDED && !outcome.isEmpty() ? PARTIAL : run.state;
        String spokenStatus = speechReason;
        schedules.call(store -> {
            var admission = new ScheduleAdmissionStore(store);
            if (ScheduleCodec.spec(run.specJson).action.speakResult) admission.recordDelivery(run.runId, "speech",
                    !blocked.isEmpty() ? "NOT_ATTEMPTED_NOTIFICATION_BLOCKED" : spokenStatus.isEmpty() ? "DELIVERED" : spokenStatus, "{}", schedules.clock().sample());
            admission.finish(run.runId, state, !blocked.isEmpty() ? DELIVERY_BLOCKED : spokenStatus.isEmpty() ? DELIVERED : DELIVERY_PARTIAL,
                    run.result, outcome.isEmpty() ? run.reason : outcome, schedules.clock().sample(), blocked.isEmpty());
            return null;
        });
    }

    private void checkCancellation() {
        if (active.isEmpty()) return;
        try {
            schedules.call(store -> {
                for (var entry : active.entrySet()) {
                    var row = store.dao().run(entry.getKey());
                    if (row == null || row.dataEpoch != store.currentEpoch() || row.state == CANCEL_REQUESTED || row.reason.equals("USER_CANCELLED_DELIVERY") || System.currentTimeMillis() >= row.expiresAt
                            || !schedules.authorized(row)) entry.getValue().token.cancel();
                }
                return null;
            });
        } catch (RuntimeException unavailable) { interruptAll(); }
    }
    private void complete(String id, Active execution) {
        synchronized (execution) {
            active.remove(id, execution);
            if (execution.cpu != null) try { execution.cpu.close(); } catch (Exception ignored) { }
            for (Runnable callback : execution.completions) callback.run();
        }
    }
    public void close() { interruptAll(); controls.shutdownNow(); workers.shutdownNow(); workflows.close(); speech.close(); }
    @Override public void interruptAll() { for (Active execution : active.values()) execution.token.cancel(); }
    private static String bounded(String value) { return new com.matrix.agent.task.redact.AuditRedactor(8192).redact(value); }
    private static Map<String, Object> parameters(String encoded) { return ScheduleCodec.arguments(encoded); }
}
