package com.matrix.agent.launcher.presentation;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import com.matrix.agent.api.common.MatrixErrorCode;
import com.matrix.agent.api.schedule.*;
import com.matrix.agent.launcher.data.ScheduleRepository;
import java.util.UUID;
import java.util.function.Consumer;

/** Retains selection and subscriptions across view recreation without taking ownership of task execution. */
public final class ScheduleViewModel extends ViewModel {
    public enum Tab { PLANS, RUNS, TEMPLATES, INSTANT }
    public record State(boolean loading, String error, ScheduleRepository.Snapshot snapshot,
            ScheduleRepository.RunDetail detail, Tab tab, String selectedPlan) { }
    private final ScheduleRepository repository;
    private final MutableLiveData<State> state = new MutableLiveData<>(new State(false, "", null, null, Tab.PLANS, ""));
    private AutoCloseable subscription;
    private boolean subscribing, cleared, reload, visible;
    private long subscriptionGeneration, planGeneration;
    private String runParent = "";
    private ScheduleRepository.PlanDetail planDetail;
    private final java.util.Map<String, android.os.Bundle> drafts = new java.util.LinkedHashMap<>();
    private boolean restoredUi;
    public android.os.Bundle draft(String key) { return drafts.get(key); }
    public void draft(String key, android.os.Bundle value) { if (value == null) drafts.remove(key); else { drafts.remove(key); drafts.put(key, new android.os.Bundle(value));
            while (drafts.size() > 8) drafts.remove(drafts.keySet().iterator().next()); } }
    public ScheduleRepository.PlanDetail planDetail() { return planDetail; }
    public void visible(boolean value) {
        visible = value;
        if (value) refresh(); else { subscriptionGeneration++; subscribing = false; close(subscription); subscription = null; }
    }
    private String requestedRun = "";
    private long detailGeneration;
    private boolean controlling, planPaging;
    private final java.util.Map<String, String> controlOperations = new java.util.HashMap<>();
    private final java.util.EnumMap<Tab, Integer> filters = new java.util.EnumMap<>(Tab.class);
    private final java.util.Map<String, Integer> scrollPositions = new java.util.HashMap<>();
    public android.os.Bundle saveUiState() {
        var saved = new android.os.Bundle(); var current = state.getValue();
        saved.putString("tab", current.tab.name()); saved.putString("plan", current.selectedPlan);
        saved.putString("run", requestedRun); saved.putString("runParent", runParent);
        var draftState = new android.os.Bundle(); drafts.forEach(draftState::putBundle); saved.putBundle("drafts", draftState);
        var scrollState = new android.os.Bundle(); scrollPositions.forEach(scrollState::putInt); saved.putBundle("scroll", scrollState);
        for (Tab tab : Tab.values()) saved.putInt("filter:" + tab.name(), filter(tab));
        return saved;
    }
    public void restoreUiState(android.os.Bundle saved) {
        if (saved == null || restoredUi) return;
        restoredUi = true;
        Tab tab;
        try { tab = Tab.valueOf(saved.getString("tab", "PLANS")); } catch (IllegalArgumentException invalid) { tab = Tab.PLANS; }
        requestedRun = saved.getString("run", ""); runParent = saved.getString("runParent", "");
        var draftState = saved.getBundle("drafts");
        if (draftState != null) for (String key : draftState.keySet()) draft(key, draftState.getBundle(key));
        var scrollState = saved.getBundle("scroll");
        if (scrollState != null) for (String key : scrollState.keySet()) scrollPositions.put(key, scrollState.getInt(key));
        for (Tab value : Tab.values()) filters.put(value, saved.getInt("filter:" + value.name()));
        var current = state.getValue();
        state.setValue(new State(false, "", current.snapshot, null, tab, saved.getString("plan", "")));
    }
    public boolean busy() { return controlling; }
    public int filter(Tab tab) { return filters.getOrDefault(tab, 0); }
    public void filter(Tab tab, int value) { if (filter(tab) != value) { filters.put(tab, value); state.setValue(state.getValue()); } }
    public int scrollPosition(String key) { return scrollPositions.getOrDefault(key, 0); }
    public void scrollPosition(String key, int value) { scrollPositions.put(key, value); }
    public boolean back() {
        var current = state.getValue();
        if (current.detail != null || !requestedRun.isEmpty()) {
            if (runParent.isEmpty()) select(Tab.RUNS); else showPlan(runParent);
            return true;
        }
        if (!current.selectedPlan.isEmpty()) { select(Tab.PLANS); return true; }
        return false;
    }
    public ScheduleViewModel(ScheduleRepository repository) { this.repository = repository; }
    public LiveData<State> state() { return state; }
    public boolean supports(int feature) {
        var current = state.getValue();
        return current.snapshot != null && (current.snapshot.featureFlags() & feature) != 0;
    }
    public boolean connected() { return repository.connected(); }
    public void select(Tab tab) {
        var old = state.getValue();
        state.setValue(new State(old.loading, old.error, old.snapshot, null, tab, ""));
        requestedRun = ""; runParent = ""; detailGeneration++; planGeneration++; planDetail = null;
        if (tab == Tab.INSTANT) { subscriptionGeneration++; subscribing = false; close(subscription); subscription = null; }
        if (tab != Tab.INSTANT) refresh();
    }
    public void showPlan(String id) {
        var old = state.getValue(); requestedRun = ""; detailGeneration++; planGeneration++;
        if (planDetail != null && !planDetail.plan().scheduleId.equals(id)) planDetail = null;
        state.setValue(new State(old.loading, "", old.snapshot, null, Tab.PLANS, id)); refresh();
    }
    public void showRun(String id) {
        if (!id.equals(requestedRun)) runParent = state.getValue().selectedPlan;
        requestedRun = id; long generation = ++detailGeneration;
        var loading = state.getValue();
        state.setValue(new State(loading.loading, loading.error, loading.snapshot, null, Tab.RUNS, ""));
        repository.detail(id, result -> {
            if (cleared || generation != detailGeneration) return;
            var old = state.getValue();
            state.setValue(new State(false, result.isSuccess() ? "" : message(result.error), old.snapshot,
                    result.value, Tab.RUNS, ""));
        });
    }
    public void refresh() {
        if (cleared || !visible || state.getValue().tab == Tab.INSTANT) return;
        if (!repository.connected()) {
            var stale = state.getValue();
            state.setValue(new State(false, "Host 未连接；以下为上次同步，操作暂不可用", stale.snapshot, stale.detail, stale.tab, stale.selectedPlan));
            return;
        }
        var old = state.getValue();
        if (old.loading) { reload = true; return; }
        state.setValue(new State(true, "", old.snapshot, old.detail, old.tab, old.selectedPlan));
        repository.load(old.snapshot, result -> {
            if (cleared) return;
            var current = state.getValue();
            state.setValue(new State(false, result.isSuccess() ? "" : message(result.error),
                    result.isSuccess() ? result.value : current.snapshot, current.detail, current.tab, current.selectedPlan));
            if (result.isSuccess()) subscribe(Math.min(result.value.plans().sequence, result.value.runs().sequence));
            if (!current.selectedPlan.isEmpty()) loadPlan(current.selectedPlan);
            if (!requestedRun.isEmpty()) showRun(requestedRun);
            if (reload) { reload = false; refresh(); }
        });
    }
    public void more(boolean plans) {
        var previous = state.getValue(); if (previous.loading || previous.snapshot == null) return;
        state.setValue(new State(true, "", previous.snapshot, previous.detail, previous.tab, previous.selectedPlan));
        repository.more(previous.snapshot, plans, reply -> {
            if (cleared) return;
            var current = state.getValue();
            state.setValue(new State(false, reply.isSuccess() ? "" : message(reply.error), reply.isSuccess() ? reply.value : current.snapshot,
                    current.detail, current.tab, current.selectedPlan));
            if (reload) { reload = false; refresh(); }
        });
    }
    private void subscribe(long sequence) {
        if (!visible || state.getValue().tab == Tab.INSTANT || subscription != null || subscribing) return;
        subscribing = true; long generation = ++subscriptionGeneration;
        repository.subscribe(sequence, this::refresh, result -> {
            if (generation != subscriptionGeneration) { close(result.value); return; }
            subscribing = false;
            if (cleared) { close(result.value); return; }
            if (result.isSuccess()) subscription = result.value;
        });
    }
    private void loadPlan(String id) {
        long generation = ++planGeneration;
        repository.planDetail(id, planDetail == null ? 100 : planDetail.runs().items.size(), result -> {
            if (cleared || generation != planGeneration || !id.equals(state.getValue().selectedPlan)) return;
            var current = state.getValue();
            if (result.isSuccess()) planDetail = result.value;
            state.setValue(new State(current.loading, result.isSuccess() ? current.error : message(result.error),
                    current.snapshot, current.detail, current.tab, current.selectedPlan));
        });
    }
    public void morePlanRuns() {
        if (planDetail == null || planDetail.runs().nextCursor.isEmpty() || planPaging) return;
        planPaging = true;
        long generation = ++planGeneration;
        repository.morePlanRuns(planDetail, result -> {
            planPaging = false;
            if (cleared || generation != planGeneration) return;
            var current = state.getValue();
            if (result.isSuccess()) planDetail = result.value;
            state.setValue(new State(false, result.isSuccess() ? "" : message(result.error), current.snapshot,
                    current.detail, current.tab, current.selectedPlan));
            if (reload) { reload = false; refresh(); }
        });
    }
    public void previewPlan(ScheduleInfo plan, Consumer<SchedulePreview> result) {
        if (plan.spec.timing.kind == ScheduleCodes.ONCE || plan.spec.timing.kind == ScheduleCodes.AFTER_DELAY
                || plan.spec.timing.kind == ScheduleCodes.CALENDAR_OFFSET) {
            var times = plan.nextDueAt > 0 ? java.util.List.of(java.time.Instant.ofEpochMilli(plan.nextDueAt)
                    .atZone(java.time.ZoneId.of(plan.spec.timing.zoneId)).toString()) : java.util.List.<String>of();
            result.accept(new SchedulePreview(0, plan.spec, times, times.isEmpty() ? "此发生已消费或当前没有待触发时间" : "以 Host 保存的发生时刻为准"));
        } else preview(plan.spec, result);
    }
    public void preview(ScheduleSpec spec, Consumer<SchedulePreview> result) {
        repository.preview(spec, reply -> result.accept(reply.isSuccess() ? reply.value
                : new SchedulePreview(MatrixErrorCode.SERVICE_NOT_READY, null, java.util.List.of(), message(reply.error))));
    }
    public void save(ScheduleInfo existing, ScheduleSpec spec, String operationId, Consumer<ScheduleMutation> result) {
        repository.save(existing, spec, operationId, reply -> {
            ScheduleMutation mutation = reply.isSuccess() ? reply.value : new ScheduleMutation(
                    MatrixErrorCode.SERVICE_NOT_READY, operationId, "", "", 0, 0, message(reply.error));
            result.accept(mutation); if (mutation.code == MatrixErrorCode.SUCCESS) refresh();
        });
    }
    public void calendar(String capability, String params, String operation, Consumer<ScheduleCalendarResult> result) {
        repository.calendar(capability, params, operation, reply -> result.accept(reply.isSuccess() ? reply.value
                : new ScheduleCalendarResult(MatrixErrorCode.SERVICE_NOT_READY, "UNAVAILABLE", "{}", message(reply.error))));
    }
    public void bindCalendar(long event, long original, String reminderOwner, String operation, Consumer<ScheduleCalendarResult> result) {
        repository.bindCalendar(event, original, reminderOwner, operation, reply -> result.accept(reply.isSuccess() ? reply.value
                : new ScheduleCalendarResult(MatrixErrorCode.SERVICE_NOT_READY, "UNAVAILABLE", "{}", message(reply.error))));
    }
    public void control(ScheduleInfo plan, String run, int operation) {
        if (controlling || !connected()) return;
        String key = plan.scheduleId + ":" + run + ":" + plan.revision + ":" + operation;
        String operationId = controlOperations.computeIfAbsent(key, ignored -> UUID.randomUUID().toString());
        controlling = true;
        state.setValue(state.getValue());
        repository.control(plan, run, operation, operationId, reply -> {
            controlling = false;
            if (reply.isSuccess() && reply.value.code != MatrixErrorCode.TIMED_OUT
                    && reply.value.code != MatrixErrorCode.SERVICE_NOT_READY) controlOperations.remove(key);
            if (cleared) return;
            if (!reply.isSuccess() || reply.value.code != MatrixErrorCode.SUCCESS) {
                var old = state.getValue();
                state.setValue(new State(false, reply.isSuccess() ? reply.value.message : message(reply.error),
                        old.snapshot, old.detail, old.tab, old.selectedPlan));
            } else refresh();
        });
    }
    private static String message(Throwable error) { return error == null || error.getMessage() == null ? "Host 暂不可用，请重试" : error.getMessage(); }
    private static void close(AutoCloseable handle) { if (handle != null) try { handle.close(); } catch (Exception ignored) { } }
    @Override protected void onCleared() { cleared = true; detailGeneration++; planGeneration++; subscriptionGeneration++; close(subscription); }
}
