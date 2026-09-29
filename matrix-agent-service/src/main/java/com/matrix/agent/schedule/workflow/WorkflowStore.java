package com.matrix.agent.schedule.workflow;

import static com.matrix.agent.api.schedule.ScheduleCodes.*;
import com.matrix.agent.data.schedule.*;
import com.matrix.agent.schedule.domain.*;
import com.matrix.agent.schedule.store.ScheduleStore;
import java.time.*;
import java.util.*;

/** Persisted step state machine and parent budget ledger. All methods are called in a Room transaction. */
public final class WorkflowStore {
    private final ScheduleStore store;
    public WorkflowStore(ScheduleStore store) { this.store = store; }
    public boolean reserveModelCall(String runId, WorkflowTemplate template) {
        var run = store.dao().run(runId);
        if (run == null || run.dataEpoch != store.currentEpoch() || run.state != RUNNING
                || run.modelCalls >= template.profile().maxIterations()) return false;
        run.modelCalls++; store.dao().updateRun(run); return true;
    }
    public void initialize(ScheduleRunEntity run, WorkflowTemplate template, ClockSample clock) {
        if (!store.dao().steps(run.runId).isEmpty()) return;
        var spec = ScheduleCodec.spec(run.specJson);
        try {
            var parameters = new org.json.JSONObject(spec.action.parametersJson);
            ZoneId zone = spec.timing.followDeviceZone ? clock.deviceZone() : ZoneId.of(spec.timing.zoneId);
            LocalDate date = Instant.ofEpochMilli(run.scheduledAt).atZone(zone).toLocalDate();
            for (var definition : template.steps()) {
                ScheduleStepEntity step = new ScheduleStepEntity();
                step.runId = run.runId; step.stepId = definition.id(); step.title = definition.title();
                step.kind = definition.kind().name(); step.dependenciesJson = new org.json.JSONArray(definition.dependencies()).toString();
                step.required = definition.required(); step.runtimeRequestId = ScheduleStore.uuid(); step.operationKey = ScheduleStore.uuid();
                step.state = WAITING_DEPENDENCY;
                org.json.JSONObject input = new org.json.JSONObject();
                if (ResearchWorkflow.isResearch(template) && definition.kind() == WorkflowTemplate.Kind.TOOL) {
                    input.put("query", parameters.getString("query")).put("source", definition.id());
                } else if (definition.kind() == WorkflowTemplate.Kind.TOOL) {
                    LocalDate queryDate = definition.id().equals("tomorrow") ? date.plusDays(1) : date;
                    input.put("startMillis", queryDate.atStartOfDay(zone).toInstant().toEpochMilli());
                    input.put("endMillis", queryDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli());
                    if (parameters.has("calendarId")) input.put("calendarId", parameters.getLong("calendarId"));
                    if (definition.id().equals("tomorrow") && !parameters.optBoolean("includeTomorrow", true)) {
                        step.state = SKIPPED; step.reason = "USER_DISABLED"; step.completedAt = clock.wall().toEpochMilli();
                    }
                }
                step.inputJson = input.toString(); store.dao().insertStep(step);
            }
        } catch (org.json.JSONException invalid) { throw new IllegalArgumentException("invalid frozen workflow parameters", invalid); }
    }
    public void recover(ScheduleRunEntity run, WorkflowTemplate template, ClockSample clock) {
        List<ScheduleStepEntity> steps = store.dao().steps(run.runId);
        long largestCap = 0;
        for (var step : steps) if (step.state == RUNNING) largestCap = Math.max(largestCap, definition(template, step.stepId).timeoutMillis());
        if (largestCap > 0) {
            long elapsed = run.bootId.equals(clock.bootId()) && run.budgetAnchorElapsed != null
                    ? Math.max(0, clock.elapsedMillis() - run.budgetAnchorElapsed) : largestCap;
            run.activeMillis += Math.min(largestCap, elapsed);
        }
        run.budgetAnchorElapsed = null; run.bootId = clock.bootId(); store.dao().updateRun(run);
        Set<String> invalidated = new HashSet<>();
        if (ResearchWorkflow.isResearch(template)) for (var definition : template.steps()) {
            var step = steps.stream().filter(row -> row.stepId.equals(definition.id())).findFirst().orElseThrow();
            boolean stale = definition.dependencies().stream().anyMatch(invalidated::contains);
            if (step.state == SUCCEEDED && definition.kind() == WorkflowTemplate.Kind.TOOL) {
                try { ResearchWorkflow.evidence(run, step, clock.wall().toEpochMilli()); }
                catch (IllegalArgumentException invalid) { stale = true; }
            }
            if (stale && definition.readOnly()) {
                invalidated.add(step.stepId); step.outputJson = "{}"; step.result = "";
                step.state = step.attempt <= definition.maxRetries() ? WAITING_DEPENDENCY : FAILED;
                step.runtimeRequestId = ScheduleStore.uuid(); step.reason = "CHECKPOINT_INVALIDATED";
                step.completedAt = step.state == FAILED ? clock.wall().toEpochMilli() : null;
                step.leaseGeneration++; store.dao().updateStep(step);
            }
        }
        for (var step : steps) {
            if (step.state == QUEUED) { step.state = WAITING_DEPENDENCY; store.dao().updateStep(step); }
            if (step.state != RUNNING) continue;
            var receipt = store.dao().acceptance(step.runtimeRequestId);
            var definition = definition(template, step.stepId);
            if (receipt != null && terminalRun(receipt.state)) {
                step.state = receipt.state; step.result = receipt.result; step.reason = receipt.reason;
                step.completedAt = receipt.completedAt == null ? clock.wall().toEpochMilli() : receipt.completedAt;
            } else if (definition.readOnly() && step.attempt <= definition.maxRetries()) {
                step.state = RETRY_WAIT; step.completedAt = null; step.nextAttemptAt = clock.wall().toEpochMilli() + 1000;
                step.reason = "PROCESS_INTERRUPTED_READ";
                if (receipt != null) { receipt.state = FAILED; receipt.reason = step.reason; receipt.completedAt = clock.wall().toEpochMilli(); store.dao().updateAcceptance(receipt); }
                step.runtimeRequestId = ScheduleStore.uuid();
            } else {
                step.state = definition.readOnly() ? FAILED : EXECUTION_UNKNOWN;
                step.completedAt = clock.wall().toEpochMilli();
                step.reason = definition.readOnly() ? "PROCESS_INTERRUPTED_READ_EXHAUSTED" : "PROCESS_INTERRUPTED_AFTER_START";
            }
            step.leaseGeneration++; step.startedElapsed = null; store.dao().updateStep(step);
        }
    }
    public List<ScheduleStepEntity> ready(ScheduleRunEntity run, WorkflowTemplate template, ClockSample clock) {
        List<ScheduleStepEntity> rows = store.dao().steps(run.runId), ready = new ArrayList<>();
        Map<String, ScheduleStepEntity> byId = new HashMap<>(); for (var row : rows) byId.put(row.stepId, row);
        for (var row : rows) {
            if (row.state != WAITING_DEPENDENCY && row.state != RETRY_WAIT && row.state != WAITING_CONDITION) continue;
            if (row.nextAttemptAt != null && row.nextAttemptAt > clock.wall().toEpochMilli()) continue;
            var definition = definition(template, row.stepId); boolean wait = false, blocked = false;
            for (String dependency : definition.dependencies()) {
                var parent = byId.get(dependency);
                if (!terminalRun(parent.state)) wait = true;
                else if (parent.required && parent.state != SUCCEEDED && parent.state != PARTIAL) blocked = true;
            }
            if (blocked) { row.state = SKIPPED; row.reason = "REQUIRED_DEPENDENCY_FAILED"; row.completedAt = clock.wall().toEpochMilli(); store.dao().updateStep(row); }
            else if (!wait) { row.state = QUEUED; row.leaseGeneration++; store.dao().updateStep(row); ready.add(row); }
        }
        return ready;
    }
    public long begin(String runId, String stepId, long lease, WorkflowTemplate template, ClockSample clock) {
        var run = store.dao().run(runId); var step = store.dao().step(runId, stepId);
        if (run == null || run.dataEpoch != store.currentEpoch() || run.state != RUNNING || step == null || step.leaseGeneration != lease || step.state != QUEUED) return 0;
        run.activeMillis = ActiveBudget.charge(run.activeMillis, run.budgetAnchorElapsed, clock.elapsedMillis());
        long allowance = ActiveBudget.allowance(run.activeMillis, definition(template, stepId).timeoutMillis(), clock.wall().toEpochMilli(), run.expiresAt, template.profile());
        if (allowance == 0) {
            step.state = FAILED; step.reason = "BUDGET_OR_EXPIRY_EXHAUSTED";
            step.completedAt = clock.wall().toEpochMilli(); store.dao().updateStep(step);
            // Charging advances the anchor even when this child cannot start; otherwise a
            // parallel child's completion would charge the same interval a second time.
            run.budgetAnchorElapsed = store.dao().steps(runId).stream().anyMatch(row -> row.state == RUNNING)
                    ? clock.elapsedMillis() : null;
            store.dao().updateRun(run); emit(run, step, "STEP_CHANGED", clock); return 0;
        }
        run.budgetAnchorElapsed = clock.elapsedMillis(); run.bootId = clock.bootId(); store.dao().updateRun(run);
        step.state = RUNNING; step.attempt++; step.completedAt = null; step.startedAt = clock.wall().toEpochMilli(); step.startedElapsed = clock.elapsedMillis();
        step.nextAttemptAt = null; store.dao().updateStep(step); emit(run, step, "STEP_STARTED", clock);
        return allowance;
    }
    public void finish(String runId, String stepId, long lease, int state, String result, String output,
            String reason, boolean retryable, WorkflowTemplate template, ClockSample clock) {
        var run = store.dao().run(runId); var step = store.dao().step(runId, stepId);
        if (run == null || run.dataEpoch != store.currentEpoch() || step == null || step.leaseGeneration != lease || step.state != RUNNING) return;
        run.activeMillis = ActiveBudget.charge(run.activeMillis, run.budgetAnchorElapsed, clock.elapsedMillis());
        step.activeMillis += step.startedElapsed == null ? 0 : Math.max(0, clock.elapsedMillis() - step.startedElapsed);
        if (ResearchWorkflow.isResearch(template) && state == SUCCEEDED
                && definition(template, stepId).kind() == WorkflowTemplate.Kind.TOOL) {
            try { output = ResearchWorkflow.checkpoint(run, step, output, clock.wall().toEpochMilli()); }
            catch (IllegalArgumentException invalid) { state = FAILED; reason = "INVALID_RESEARCH_EVIDENCE"; output = "{}"; }
        }
        step.startedElapsed = null; step.state = state; step.result = result; step.outputJson = output; step.reason = reason;
        step.completedAt = clock.wall().toEpochMilli();
        var receipt = store.dao().acceptance(step.runtimeRequestId);
        if (receipt != null) { receipt.state = state; receipt.result = result; receipt.reason = reason; receipt.completedAt = step.completedAt; store.dao().updateAcceptance(receipt); }
        var definition = definition(template, stepId);
        if (retryable && definition.readOnly() && step.attempt <= definition.maxRetries() && run.state != CANCEL_REQUESTED
                && ActiveBudget.allowance(run.activeMillis, definition.timeoutMillis(), clock.wall().toEpochMilli(), run.expiresAt, template.profile()) > 1000) {
            step.state = RETRY_WAIT; step.nextAttemptAt = clock.wall().toEpochMilli() + Math.min(30_000, 1000L << (step.attempt - 1));
            step.runtimeRequestId = ScheduleStore.uuid();
        }
        store.dao().updateStep(step);
        boolean stillActive = store.dao().steps(runId).stream().anyMatch(row -> row.state == RUNNING);
        run.budgetAnchorElapsed = stillActive ? clock.elapsedMillis() : null;
        store.dao().updateRun(run); emit(run, step, "STEP_CHANGED", clock);
    }
    public void stopWaiting(ScheduleRunEntity run, String reason, ClockSample clock) {
        for (var step : store.dao().steps(run.runId)) if (!terminalRun(step.state) && step.state != RUNNING) {
            step.state = CANCELLED; step.reason = reason; step.completedAt = clock.wall().toEpochMilli(); store.dao().updateStep(step);
        }
    }
    public static WorkflowTemplate.Step definition(WorkflowTemplate template, String id) {
        return template.steps().stream().filter(step -> step.id().equals(id)).findFirst().orElseThrow(() -> new IllegalStateException("frozen step unavailable"));
    }
    private void emit(ScheduleRunEntity run, ScheduleStepEntity step, String kind, ClockSample clock) {
        store.event(store.dao().definition(run.scheduleId), run.runId, kind, step.stepId + ":" + step.state, clock.wall().toEpochMilli());
    }
}
