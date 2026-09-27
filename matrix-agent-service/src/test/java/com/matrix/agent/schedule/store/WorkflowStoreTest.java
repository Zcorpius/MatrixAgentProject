package com.matrix.agent.schedule.store;

import static org.junit.Assert.*;
import static com.matrix.agent.api.schedule.ScheduleCodes.*;
import com.matrix.agent.api.schedule.*;
import com.matrix.agent.identity.*;
import com.matrix.agent.schedule.domain.*;
import com.matrix.agent.schedule.workflow.*;
import java.time.*;
import java.util.*;
import org.junit.Test;

public final class WorkflowStoreTest {
    private final ScheduleStoreFixture database = new ScheduleStoreFixture();
    private final ScheduleStore store = new ScheduleStore(database, () -> 1, () -> false);
    private final WorkflowStore workflow = new WorkflowStore(store);
    private final WorkflowTemplate template = WorkflowCatalog.require("daily_agenda", 1);
    private final Instant origin = Instant.parse("2026-09-27T08:00:00Z");
    private ClockSample clock(long millis) { return new ClockSample(origin.plusMillis(millis), millis + 1000, "boot", ZoneId.of("UTC")); }
    private String initialize() {
        var spec = new ScheduleSpec("workflow", new ScheduleTiming(ONCE, "UTC", origin.plusSeconds(1).toEpochMilli(), 0, "", 0, "", "", false, "", 0),
                new ScheduleAction(WORKFLOW, "agenda", "daily_agenda", 1, "{}", List.of("calendar.query"), false, false), 600_000, WITHIN_GRACE);
        var identity = new ScheduleIdentity(10001, 0, "test.owner", "signature", Actor.DRIVER, VehicleZone.DRIVER);
        String plan = store.create(identity, spec, UUID.randomUUID().toString(), clock(0)).scheduleId;
        var admission = new ScheduleAdmissionStore(store); admission.reconcile(clock(1000), false, true);
        admission.admitOne("admit:" + plan, clock(1000), clock(1000).wall().toEpochMilli(), clock(1000).elapsedMillis());
        var run = store.dao().occurrence(plan, store.dao().definition(plan).fixedOccurrenceKey);
        run.state = RUNNING; store.dao().updateRun(run); workflow.initialize(run, template, clock(1000));
        return run.runId;
    }
    @Test public void optionalReadFailureDoesNotReleaseSummaryUntilRequiredReadFinishes() {
        String id = initialize(); var run = store.dao().run(id);
        var ready = workflow.ready(run, template, clock(1000)); assertEquals(2, ready.size());
        for (var step : ready) assertTrue(workflow.begin(id, step.stepId, step.leaseGeneration, template, clock(1000)) > 0);
        var tomorrow = store.dao().step(id, "tomorrow");
        workflow.finish(id, "tomorrow", tomorrow.leaseGeneration, FAILED, "unavailable", "{}", "READ_FAILED", false, template, clock(2000));
        assertTrue(workflow.ready(store.dao().run(id), template, clock(2000)).isEmpty());
        var today = store.dao().step(id, "today");
        workflow.finish(id, "today", today.leaseGeneration, SUCCEEDED, "ok", "{}", "", false, template, clock(3000));
        assertEquals(2000, store.dao().run(id).activeMillis);
        var summary = workflow.ready(store.dao().run(id), template, clock(4000));
        assertEquals(1, summary.size()); assertEquals("summary", summary.get(0).stepId);
    }
    @Test public void cancellationStopsWaitingChildrenAndStaleLeaseCannotOverwriteCompletedStep() {
        String id = initialize(); var ready = workflow.ready(store.dao().run(id), template, clock(1000));
        var today = ready.stream().filter(step -> step.stepId.equals("today")).findFirst().orElseThrow();
        workflow.begin(id, "today", today.leaseGeneration, template, clock(1000));
        workflow.stopWaiting(store.dao().run(id), "CANCELLED", clock(1500));
        assertEquals(CANCELLED, store.dao().step(id, "summary").state);
        workflow.finish(id, "today", today.leaseGeneration, SUCCEEDED, "original", "{}", "", false, template, clock(2000));
        workflow.finish(id, "today", today.leaseGeneration, FAILED, "late", "{}", "", false, template, clock(3000));
        assertEquals("original", store.dao().step(id, "today").result);
        assertTrue(workflow.ready(store.dao().run(id), template, clock(4000)).isEmpty());
    }
    @Test public void recoveryRetainsBudgetAndNeverReplaysAnUncertainDelivery() {
        String id = initialize();
        var today = store.dao().step(id, "today"); today.state = RUNNING; today.attempt = 3; store.dao().updateStep(today);
        var delivery = store.dao().step(id, "deliver"); delivery.state = RUNNING; delivery.attempt = 1; store.dao().updateStep(delivery);
        var run = store.dao().run(id); run.activeMillis = 5000; run.budgetAnchorElapsed = clock(1000).elapsedMillis(); store.dao().updateRun(run);
        workflow.recover(run, template, clock(6000));
        assertEquals(10_000, store.dao().run(id).activeMillis);
        assertEquals(FAILED, store.dao().step(id, "today").state);
        assertEquals(EXECUTION_UNKNOWN, store.dao().step(id, "deliver").state);
    }
    @Test public void retryWaitDoesNotBurnParentBudgetAndNewAttemptHasNoStaleCompletionTime() {
        String id = initialize(); var ready = workflow.ready(store.dao().run(id), template, clock(1000));
        var today = ready.stream().filter(step -> step.stepId.equals("today")).findFirst().orElseThrow();
        workflow.begin(id, "today", today.leaseGeneration, template, clock(1000));
        workflow.finish(id, "today", today.leaseGeneration, FAILED, "", "{}", "TIMEOUT", true, template, clock(2000));
        assertEquals(RETRY_WAIT, store.dao().step(id, "today").state);
        var retry = workflow.ready(store.dao().run(id), template, clock(20000)).get(0);
        assertTrue(workflow.begin(id, retry.stepId, retry.leaseGeneration, template, clock(20000)) > 0);
        assertNull(store.dao().step(id, "today").completedAt);
        assertEquals(1000, store.dao().run(id).activeMillis);
        workflow.finish(id, "today", retry.leaseGeneration, SUCCEEDED, "ok", "{}", "", false, template, clock(21000));
        assertEquals(2000, store.dao().run(id).activeMillis);
        assertEquals(2, store.dao().step(id, "today").attempt);
    }
    @Test public void rejectedParallelStartDoesNotChargeTheSameActiveIntervalTwice() {
        String id = initialize(); var ready = workflow.ready(store.dao().run(id), template, clock(1000));
        var today = ready.stream().filter(step -> step.stepId.equals("today")).findFirst().orElseThrow();
        var tomorrow = ready.stream().filter(step -> step.stepId.equals("tomorrow")).findFirst().orElseThrow();
        var run = store.dao().run(id); run.activeMillis = 119000; store.dao().updateRun(run);
        assertEquals(1000, workflow.begin(id, "today", today.leaseGeneration, template, clock(1000)));
        assertEquals(0, workflow.begin(id, "tomorrow", tomorrow.leaseGeneration, template, clock(2000)));
        assertEquals(Long.valueOf(clock(2000).wall().toEpochMilli()), store.dao().step(id, "tomorrow").completedAt);
        workflow.finish(id, "today", today.leaseGeneration, FAILED, "", "{}", "BUDGET", false, template, clock(2000));
        assertEquals(120000, store.dao().run(id).activeMillis);
    }

}
