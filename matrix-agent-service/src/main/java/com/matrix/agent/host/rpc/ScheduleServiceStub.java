package com.matrix.agent.host.rpc;

import static com.matrix.agent.api.common.MatrixErrorCode.*;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import com.matrix.agent.api.schedule.*;
import com.matrix.agent.host.di.ScheduleCallerIdentity;
import com.matrix.agent.host.di.ScheduleGraph;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.VehicleZone;
import com.matrix.agent.schedule.domain.*;
import com.matrix.agent.schedule.store.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Authenticated, bounded scheduling facade. It never forwards text to the conversation channel. */
public final class ScheduleServiceStub extends IScheduleService.Stub implements AutoCloseable {
    private final Context context;
    private final ScheduleGraph graph;
    private final com.matrix.agent.host.di.CalendarBindingGateway calendars;
    private final RemoteCallbackList<IScheduleCallback> callbacks = new RemoteCallbackList<>();
    private final java.util.concurrent.ExecutorService events = Executors.newSingleThreadExecutor(r -> new Thread(r, "matrix-schedule-events"));
    private final AtomicBoolean draining = new AtomicBoolean();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private final Runnable observer = this::publish;
    private static final class Subscription { final int owner; long sequence; Subscription(int owner, long sequence) { this.owner = owner; this.sequence = sequence; } }
    public ScheduleServiceStub(Context context, ScheduleGraph graph) { this.context = context; this.graph = graph;
        calendars = ((com.matrix.agent.host.MatrixAgentApplication) context.getApplicationContext()).calendarBindings();
        graph.addObserver(observer); }
    private ScheduleIdentity caller() {
        // Current Launcher is the driver surface. Future passenger surfaces receive a separate trusted mapping.
        return ScheduleCallerIdentity.capture(context, Actor.DRIVER, VehicleZone.DRIVER);
    }
    @Override public ScheduleReadiness getReadiness() {
        caller();
        graph.changed();
        boolean available;
        try { available = graph.call(store -> { store.checkAvailable(); return true; }); }
        catch (RuntimeException failure) { return new ScheduleReadiness(code(failure), false, false, graph.userReady(), false, false, safe(failure)); }
        return new ScheduleReadiness(available ? SUCCESS : PERSISTENCE_UNAVAILABLE,
                graph.alarms().allowed(), graph.notifications().allowed(), graph.userReady(),
                permission(Manifest.permission.READ_CALENDAR), permission(Manifest.permission.WRITE_CALENDAR), "");
    }
    private boolean permission(String permission) { return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED; }
    @Override public SchedulePreview preview(ScheduleSpec spec) {
        var owner = caller();
        try {
            var clock = graph.clock().sample(); var normalized = new ScheduleNormalizer().normalize(spec, clock);
            graph.call(store -> { store.validateAction(normalized.spec().action); return null; });
            List<String> times = new ArrayList<>(); var after = clock.wall();
            if (normalized.rule() instanceof TimeRule.CalendarOffset calendar) {
                var binding = graph.call(store -> new CalendarBindingStore(store).owned(owner.uid(), calendar.bindingId()));
                if (!binding.state.equals("VALID")) throw new IllegalArgumentException("日历绑定暂不可用，请先刷新源");
                var source = CalendarSnapshot.decode(binding.sourceRevision);
                times.add(java.time.Instant.ofEpochMilli(source.startMillis() - calendar.offsetMillis()).atZone(java.time.ZoneId.of(spec.timing.zoneId)).toString());
            } else for (int i = 0; i < 5; i++) {
                var next = OccurrenceCalculator.next(normalized.rule(), after, 1, "preview", clock);
                if (next.isEmpty()) break;
                times.add(next.get().scheduledAt().atZone(java.time.ZoneId.of(spec.timing.zoneId)).toString());
                after = next.get().scheduledAt();
            }
            return new SchedulePreview(SUCCESS, normalized.spec(), times, "预览不会启用计划");
        } catch (RuntimeException invalid) { return new SchedulePreview(code(invalid), null, List.of(), safe(invalid)); }
    }
    @Override public ScheduleMutation create(ScheduleSpec spec, String operationId) {
        ScheduleIdentity identity = caller();
        return mutate(operationId, () -> graph.call(store -> store.create(identity, spec, operationId, graph.clock().sample())));
    }
    @Override public ScheduleMutation update(String id, long revision, ScheduleSpec spec, String operationId) {
        ScheduleIdentity identity = caller();
        return mutate(operationId, () -> graph.call(store -> store.update(identity, id, revision, spec, operationId, graph.clock().sample())));
    }
    @Override public ScheduleMutation control(String id, String run, long revision, int command, String operationId) {
        ScheduleIdentity identity = caller();
        return mutate(operationId, () -> graph.call(store -> store.control(identity.uid(), id, run, revision, command, operationId, graph.clock().sample())));
    }
    private ScheduleMutation mutate(String operationId, Supplier<ScheduleMutation> action) {
        try {
            if (!graph.userReady()) throw new ScheduleFailure(PERMISSION_DENIED, "仅支持当前前台 Android user 0");
            ScheduleMutation result = action.get(); graph.changed(); return result;
        } catch (RuntimeException failure) { return new ScheduleMutation(code(failure), operationId, "", "", 0, 0, safe(failure)); }
    }
    @Override public ScheduleInfo get(String id) {
        int owner = caller().uid();
        return read(() -> graph.call(store -> ScheduleProjection.plan(store.owned(owner, id), store.dao().sequence(owner))));
    }
    @Override public SchedulePage list(String cursor, int limit) {
        int owner = caller().uid();
        try {
            int count = limit(limit); String after = cursor == null ? "" : cursor;
            if (!after.isEmpty()) ScheduleNormalizer.requireUuid(after);
            return graph.call(store -> store.database().runInTransaction(() -> {
                store.checkAvailable(); long sequence = store.dao().sequence(owner);
                var rows = store.dao().definitions(owner, after, count + 1);
                List<ScheduleInfo> items = new ArrayList<>();
                var budget = new SchedulePageBudget();
                for (int i = 0; i < Math.min(count, rows.size()); i++) {
                    var item = ScheduleProjection.plan(rows.get(i), sequence);
                    if (!budget.include(item)) break; items.add(item);
                }
                return new SchedulePage(SUCCESS, items, rows.size() > items.size() ? items.get(items.size() - 1).scheduleId : "", sequence);
            }));
        } catch (RuntimeException failure) { return new SchedulePage(code(failure), List.of(), "", 0); }
    }
    @Override public ScheduleRunInfo getRun(String id) {
        int owner = caller().uid();
        return read(() -> graph.call(store -> ScheduleProjection.run(store.ownedRun(owner, id), store.dao().sequence(owner))));
    }
    @Override public ScheduleRunPage listRuns(String schedule, String cursor, int limit) {
        int owner = caller().uid();
        try {
            int count = limit(limit); String selected = schedule == null ? "" : schedule;
            String[] pieces = cursor == null || cursor.isEmpty() ? new String[]{Long.toString(Long.MAX_VALUE), "~"} : cursor.split("/", -1);
            if (pieces.length != 2) throw new IllegalArgumentException("无效分页游标");
            long before = Long.parseLong(pieces[0]);
            if (!pieces[1].equals("~")) ScheduleNormalizer.requireUuid(pieces[1]);
            return graph.call(store -> store.database().runInTransaction(() -> {
                store.checkAvailable(); if (!selected.isEmpty()) store.owned(owner, selected);
                long sequence = store.dao().sequence(owner);
                var rows = store.dao().runs(owner, selected, before, pieces[1], count + 1);
                List<ScheduleRunInfo> items = new ArrayList<>();
                var budget = new SchedulePageBudget();
                for (int i = 0; i < Math.min(count, rows.size()); i++) {
                    var item = ScheduleProjection.run(rows.get(i), sequence);
                    if (!budget.include(item)) break; items.add(item);
                }
                ScheduleRunInfo last = items.isEmpty() ? null : items.get(items.size() - 1);
                return new ScheduleRunPage(SUCCESS, items, rows.size() > items.size() ? last.scheduledAt + "/" + last.runId : "", sequence);
            }));
        } catch (RuntimeException failure) { return new ScheduleRunPage(code(failure), List.of(), "", 0); }
    }
    @Override public List<ScheduleStepInfo> getSteps(String runId) {
        int owner = caller().uid();
        return read(() -> graph.call(store -> {
            var run = store.ownedRun(owner, runId);
            List<ScheduleStepInfo> result = new ArrayList<>();
            var action = ScheduleCodec.spec(run.specJson).action;
            if (action.kind == ScheduleCodes.WORKFLOW) {
                var template = com.matrix.agent.schedule.workflow.WorkflowCatalog.require(action.templateId, action.templateVersion);
                var rows = store.dao().steps(runId);
                for (var definition : template.steps()) {
                    for (var row : rows) if (definition.id().equals(row.stepId)) result.add(ScheduleProjection.step(row));
                }
            }
            return result;
        }));
    }
    @Override public List<ScheduleTemplateInfo> listTemplates() { caller(); return com.matrix.agent.schedule.workflow.WorkflowCatalog.describe(); }
    @Override public ScheduleCalendarResult calendar(String capability, String parameters, String operationId) {
        var identity = caller(); return calendarResult(() -> calendars.calendar(identity, capability, parameters, operationId));
    }
    @Override public ScheduleCalendarResult bindCalendar(long event, long original, String reminderOwner, String operationId) {
        var identity = caller(); return calendarResult(() -> calendars.bind(identity, event, original, reminderOwner, operationId));
    }
    @Override public ScheduleCalendarResult calendarBindings() {
        var identity = caller(); return calendarResult(() -> calendars.bindings(identity));
    }
    private ScheduleCalendarResult calendarResult(Supplier<ScheduleCalendarResult> operation) {
        try {
            if (!graph.userReady()) throw new ScheduleFailure(PERMISSION_DENIED, "仅支持前台 user 0");
            return operation.get();
        } catch (RuntimeException failure) { return new ScheduleCalendarResult(code(failure), "FAILED", "{}", safe(failure)); }
    }
    @Override public void subscribe(long afterSequence, IScheduleCallback callback) {
        int owner = caller().uid();
        if (callback == null || afterSequence < 0) throw new IllegalArgumentException("无效订阅");
        synchronized (callbacks) {
            if (callbacks.getRegisteredCallbackCount() >= 64) throw new IllegalStateException("订阅数量超限");
            callbacks.register(callback, new Subscription(owner, afterSequence));
        }
        publish();
    }
    @Override public void unsubscribe(IScheduleCallback callback) { caller(); if (callback != null) callbacks.unregister(callback); }
    private void publish() {
        dirty.set(true);
        if (!draining.compareAndSet(false, true)) return;
        events.execute(() -> {
            try {
                dirty.set(false);
                int count = callbacks.beginBroadcast();
                try {
                    for (int i = 0; i < count; i++) {
                        Subscription subscription = (Subscription) callbacks.getBroadcastCookie(i);
                        long first = graph.call(store -> store.dao().firstSequence(subscription.owner));
                        if (subscription.sequence > 0 && first > subscription.sequence + 1) {
                            long latest = graph.call(store -> store.dao().sequence(subscription.owner));
                            try { callbacks.getBroadcastItem(i).onChanged(new ScheduleEvent(latest, "", "", "RESYNC_REQUIRED")); }
                            catch (RemoteException dead) { continue; }
                            subscription.sequence = latest;
                        }
                        var batch = graph.call(store -> store.dao().events(subscription.owner, subscription.sequence, 100));
                        if (batch.size() == 100) dirty.set(true);
                        for (var row : batch) {
                            try { callbacks.getBroadcastItem(i).onChanged(new ScheduleEvent(row.sequence, row.scheduleId, row.runId, row.kind)); }
                            catch (RemoteException dead) { break; }
                            subscription.sequence = row.sequence;
                        }
                    }
                } finally { callbacks.finishBroadcast(); }
            } catch (RuntimeException unavailable) { /* Reconnect and snapshots repair event delivery. */ }
            finally { draining.set(false); if (dirty.get() && !events.isShutdown()) publish(); }
        });
    }
    private static int limit(int value) { if (value < 1 || value > 100) throw new IllegalArgumentException("分页大小应在 1 至 100 之间"); return value; }
    private static int code(RuntimeException error) {
        if (error instanceof ScheduleFailure failure) return failure.code();
        if (error instanceof SecurityException) return PERMISSION_DENIED;
        if (error instanceof IllegalArgumentException || error instanceof NullPointerException) return INVALID_ARGUMENT;
        return PERSISTENCE_UNAVAILABLE;
    }
    private static String safe(RuntimeException failure) {
        return failure instanceof ScheduleFailure || failure instanceof IllegalArgumentException
                ? failure.getMessage() : "计划服务暂不可用";
    }
    private static <T> T read(Supplier<T> action) {
        try { return action.get(); }
        catch (RuntimeException failure) { throw new IllegalStateException("计划查询失败（" + code(failure) + "）：" + safe(failure)); }
    }
    @Override public void close() { graph.removeObserver(observer); callbacks.kill(); events.shutdownNow(); }
}
