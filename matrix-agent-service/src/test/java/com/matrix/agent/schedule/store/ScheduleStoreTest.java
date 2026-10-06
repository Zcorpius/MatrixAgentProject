package com.matrix.agent.schedule.store;

import static org.junit.Assert.*;
import static com.matrix.agent.api.schedule.ScheduleCodes.*;
import com.matrix.agent.api.schedule.*;
import com.matrix.agent.identity.*;
import com.matrix.agent.schedule.domain.*;
import com.matrix.agent.schedule.execution.*;
import java.time.*;
import java.util.*;
import org.junit.Test;

public final class ScheduleStoreTest {
    private final ScheduleStoreFixture db = new ScheduleStoreFixture();
    private final ScheduleStore store = new ScheduleStore(db, () -> 1, () -> false);
    private final ScheduleAdmissionStore admission = new ScheduleAdmissionStore(store);
    private final ScheduleIdentity owner = new ScheduleIdentity(10001, 0, "test.owner", "signature", Actor.PASSENGER, VehicleZone.PASSENGER);
    private final Instant origin = Instant.parse("2026-09-27T08:00:00Z");
    private ClockSample clock(long seconds) { return new ClockSample(origin.plusSeconds(seconds), 1000 + seconds * 1000, "boot", ZoneId.of("UTC")); }
    private ScheduleSpec once(long seconds) { return new ScheduleSpec("reminder", new ScheduleTiming(ONCE, "UTC", origin.plusSeconds(seconds).toEpochMilli(), 0, "", 0, "", "", false, "", 0),
            new ScheduleAction(NOTIFICATION, "reminder", "", 0, "{}", List.of(), false, false), 600_000, WITHIN_GRACE); }
    private String create(long seconds) { return store.create(owner, once(seconds), UUID.randomUUID().toString(), clock(0)).scheduleId; }
    private void admit(String id, long seconds) { admission.reconcile(clock(seconds), false, true); admission.attempted("admit:" + id, clock(seconds), "started"); assertTrue(admission.admitOne("admit:" + id, clock(seconds), clock(seconds).wall().toEpochMilli(), clock(seconds).elapsedMillis())); }
    @Test public void weatherPlanWaitsInDraftUntilContentAdmissionIsRestored() {
        var block = new java.util.concurrent.atomic.AtomicReference<>("WEATHER_NOT_CONFIGURED");
        var guarded = new ScheduleStore(db, () -> 1, () -> false, action -> { }, () -> "", action -> block.get());
        var action = new ScheduleAction(WORKFLOW, "查询本次城市天气", "daily_weather_current", 1,
                "{\"mode\":\"CURRENT_AT_TRIGGER\",\"allowLocation\":true,\"allowWeatherNetwork\":true}",
                List.of("location.resolve_city", "weather.today"), true, false);
        var original = once(60);
        var spec = new ScheduleSpec("天气提醒", original.timing, action, original.graceMillis, original.misfirePolicy);
        String id = guarded.create(owner, spec, UUID.randomUUID().toString(), clock(0)).scheduleId;
        assertEquals(DRAFT, guarded.dao().definition(id).state);
        assertEquals("WEATHER_NOT_CONFIGURED", guarded.dao().definition(id).reason);
        assertThrows(ScheduleFailure.class, () -> guarded.control(owner.uid(), id, "", guarded.dao().definition(id).revision,
                RESUME, UUID.randomUUID().toString(), clock(1)));
        block.set("");
        var latest = guarded.dao().definition(id);
        guarded.control(owner.uid(), id, "", latest.revision, RESUME, UUID.randomUUID().toString(), clock(2));
        assertEquals(ACTIVE, guarded.dao().definition(id).state);
    }
    @Test public void queuedFixedRescheduleReusesBothIdentitiesAndInvalidatesOldGeneration() {
        String id = create(10); admit(id, 10); var plan = store.dao().definition(id); var first = store.dao().occurrence(id, plan.fixedOccurrenceKey);
        String request = first.runtimeRequestId;
        store.update(owner, id, plan.revision, once(60), UUID.randomUUID().toString(), clock(11));
        var moved = store.dao().run(first.runId); assertEquals(WAITING_TRIGGER, moved.state); assertEquals(request, moved.runtimeRequestId);
        assertEquals(first.occurrenceKey, moved.occurrenceKey); assertNull(moved.receivedAt); assertTrue(moved.dispatchGeneration > first.dispatchGeneration);
        assertNull(store.dao().outbox("dispatch:" + first.runId));
        admit(id, 60); assertEquals(first.runId, store.dao().occurrence(id, plan.fixedOccurrenceKey).runId);
        assertNotNull(admission.claim("dispatch:" + first.runId, clock(60)));
        var latest = store.dao().definition(id);
        assertThrows(ScheduleFailure.class, () -> store.update(owner, id, latest.revision, once(120), UUID.randomUUID().toString(), clock(61)));
    }
    @Test public void lostAcceptanceReplyReusesStableHandleAndConflictsOnDifferentPayload() {
        String id = create(10); admit(id, 10); var plan = store.dao().definition(id); var run = store.dao().occurrence(id, plan.fixedOccurrenceKey);
        admission.claim("dispatch:" + run.runId, clock(10));
        var port = new DurableRuntimeExecutionPort(store, () -> clock(10).wall().toEpochMilli());
        var auth = new RuntimeExecutionPort.AuthorizationSnapshot(run.authorizationJson, Actor.PASSENGER, VehicleZone.PASSENGER);
        var context = new RuntimeExecutionPort.ExecutionContext("", 1, run.expiresAt);
        var first = port.accept(run.runtimeRequestId, run.runId, ScheduleCodec.spec(run.specJson), auth, context);
        assertEquals(first, port.accept(run.runtimeRequestId, run.runId, ScheduleCodec.spec(run.specJson), auth, context));
        assertEquals(first, port.getAcceptance(run.runtimeRequestId)); assertNotNull(store.dao().outbox("execute:" + run.runId));
        assertThrows(ScheduleFailure.class, () -> port.accept(run.runtimeRequestId, run.runId, once(100), auth, context));
    }
    @Test public void dueContinuationCompetesWithFutureArmAndDoesNotPostponeUntouchedSiblings() {
        String first = create(10), second = create(10); create(100);
        var arm = admission.reconcile(clock(10), false, true); assertEquals(Long.valueOf(origin.plusSeconds(10).toEpochMilli()), arm.desiredDueAt);
        admission.attempted("admit:" + first, clock(10), "retry");
        var next = admission.reconcile(clock(10), false, true);
        assertEquals(Long.valueOf(origin.plusSeconds(10).toEpochMilli()), next.desiredDueAt);
        assertEquals(0, store.dao().outbox("admit:" + second).attempt);
    }
    @Test public void replayDoesNotReanchorAfterDelayAndBackwardClockDoesNotLoseIt() {
        var spec = new ScheduleSpec("delay", new ScheduleTiming(AFTER_DELAY, "UTC", 0, 60_000, "", 0, "", "", false, "", 0), once(10).action, 600_000, WITHIN_GRACE);
        String operation = UUID.randomUUID().toString(); var created = store.create(owner, spec, operation, clock(0));
        assertEquals(created.scheduleId, store.create(owner, spec, operation, clock(20)).scheduleId);
        ClockSample backwards = new ClockSample(origin.minusSeconds(3600), 21_000, "boot", ZoneId.of("UTC"));
        admission.reconcile(backwards, true, true);
        var plan = store.dao().definition(created.scheduleId);
        assertEquals(Long.valueOf(61_000), plan.nextElapsedAt);
        assertEquals(Long.valueOf(backwards.wall().plusSeconds(40).toEpochMilli()), plan.nextDueAt);
    }
    @Test public void cancelPendingDeliveryRejectsLateCallbackWithoutRewritingExecution() {
        String id = create(10); admit(id, 10); var plan=store.dao().definition(id);
        var run=store.dao().occurrence(id,plan.fixedOccurrenceKey); run.state=SUCCEEDED; run.completedAt=clock(11).wall().toEpochMilli(); store.dao().updateRun(run);
        store.control(owner.uid(),id,run.runId,plan.revision,CANCEL_RUN,UUID.randomUUID().toString(),clock(12));
        admission.finish(run.runId,SUCCEEDED,DELIVERED,"late","",clock(13),true);
        var retained=store.dao().run(run.runId); assertEquals(SUCCEEDED,retained.state); assertEquals(DELIVERY_NOT_REQUIRED,retained.deliveryStatus);
        assertNull(retained.deliveredAt); assertEquals("USER_CANCELLED_DELIVERY",retained.reason);
    }
    @Test public void calendarUnclaimedMovePreservesIdentityAndFrozenActionThenDeletionStopsIt() {
        var bindings = new CalendarBindingStore(store);
        var source=new CalendarSnapshot(origin.plusSeconds(10).toEpochMilli(),origin.plusSeconds(3610).toEpochMilli(),"v1","meeting",false);
        var binding=bindings.bind(owner,1,2,source.startMillis(),"AGENT",source,UUID.randomUUID().toString(),clock(0));
        var spec=new ScheduleSpec("calendar",new ScheduleTiming(CALENDAR_OFFSET,"UTC",0,0,"",0,"","",false,binding.bindingId,0),once(10).action,600000,WITHIN_GRACE);
        String id=store.create(owner,spec,UUID.randomUUID().toString(),clock(0)).scheduleId;
        admission.reconcile(clock(10),false,true);
        assertFalse(admission.admitCalendar("admit:"+id,clock(10),clock(10).wall().toEpochMilli(),clock(10).elapsedMillis()));
        bindings.refresh(binding.bindingId,1,source,"VALID",clock(10));
        assertTrue(admission.admitCalendar("admit:"+id,clock(10),clock(10).wall().toEpochMilli(),clock(10).elapsedMillis()));
        var plan=store.dao().definition(id); var first=store.dao().occurrence(id,plan.fixedOccurrenceKey);
        var movedSource=new CalendarSnapshot(origin.plusSeconds(60).toEpochMilli(),origin.plusSeconds(3660).toEpochMilli(),"v2","moved",false);
        bindings.refresh(binding.bindingId,1,movedSource,"VALID",clock(11));
        var moved=store.dao().run(first.runId); assertEquals(WAITING_TRIGGER,moved.state); assertEquals(first.runtimeRequestId,moved.runtimeRequestId);
        assertEquals(first.requestHash,moved.requestHash); assertEquals(first.specJson,moved.specJson);
        assertEquals(movedSource.startMillis(),moved.scheduledAt); assertNull(admission.claim("dispatch:"+first.runId,clock(12)));
        bindings.refresh(binding.bindingId,1,null,"DELETED",clock(13));
        assertEquals(CANCELLED,store.dao().run(first.runId).state); assertNull(store.dao().definition(id).nextDueAt);
    }
    @Test public void oldEpochCannotFinishOrAdmitAfterResetAndAnotherOwnerCannotRead() {
        java.util.concurrent.atomic.AtomicLong epoch=new java.util.concurrent.atomic.AtomicLong(1);
        var guarded=new ScheduleStore(db,epoch::get,()->false); var gate=new ScheduleAdmissionStore(guarded);
        String id=create(10); admit(id,10); var plan=store.dao().definition(id); var run=store.dao().occurrence(id,plan.fixedOccurrenceKey);
        assertThrows(ScheduleFailure.class,()->guarded.owned(20000,id)); epoch.incrementAndGet();
        gate.finish(run.runId,SUCCEEDED,DELIVERED,"late","",clock(12),true);
        assertEquals(QUEUED,store.dao().run(run.runId).state); assertNull(gate.claim("dispatch:"+run.runId,clock(12)));
    }

    @Test public void consumedPhysicalAlarmIsRearmedEvenWhenUntouchedBacklogHasSameTarget() {
        create(10);
        var original = admission.reconcile(clock(10), false, true);
        admission.acknowledgeArm(1, original.armGeneration, "");
        assertEquals(original.armGeneration, admission.reconcile(clock(10), false, true).armGeneration);
        admission.invalidateArm();
        var replacement = admission.reconcile(clock(10), false, true);
        assertEquals(original.desiredDueAt, replacement.desiredDueAt);
        assertTrue(replacement.armGeneration > original.armGeneration);
        assertEquals(PENDING, replacement.state);
    }
    @Test public void freshAcceptanceCannotExtendExpiryButCommittedReceiptRemainsQueryableAfterExpiry() {
        String id = create(10); admit(id, 10);
        var run = store.dao().occurrence(id, store.dao().definition(id).fixedOccurrenceKey);
        admission.claim("dispatch:" + run.runId, clock(10));
        var now = new java.util.concurrent.atomic.AtomicLong(clock(10).wall().toEpochMilli());
        var port = new DurableRuntimeExecutionPort(store, now::get);
        var auth = new RuntimeExecutionPort.AuthorizationSnapshot(run.authorizationJson, Actor.PASSENGER, VehicleZone.PASSENGER);
        var spec = ScheduleCodec.spec(run.specJson);
        assertThrows(ScheduleFailure.class, () -> port.accept(run.runtimeRequestId, run.runId, spec, auth,
                new RuntimeExecutionPort.ExecutionContext("", 1, run.expiresAt + 1)));
        now.set(run.expiresAt + 1);
        var context = new RuntimeExecutionPort.ExecutionContext("", 1, run.expiresAt);
        assertThrows(ScheduleFailure.class, () -> port.accept(run.runtimeRequestId, run.runId, spec, auth, context));
        now.set(run.expiresAt);
        var committed = port.accept(run.runtimeRequestId, run.runId, spec, auth, context);
        now.incrementAndGet();
        assertEquals(committed, port.accept(run.runtimeRequestId, run.runId, spec, auth, context));
    }

    @Test public void queuedCreateCannotResurrectDataAfterResetMarkerHasCleared() {
        var epoch = new java.util.concurrent.atomic.AtomicLong(1);
        var pending = new java.util.concurrent.atomic.AtomicBoolean();
        var guarded = new ScheduleStore(db, epoch::get, pending::get);
        var fence = new ScheduleCommandFence(); long queued = fence.capture();
        create(10);
        pending.set(true);
        db.runInTransaction(() -> { db.clearAllTables(); epoch.incrementAndGet(); });
        fence.invalidate(); pending.set(false);
        String operation = UUID.randomUUID().toString();
        assertThrows(ScheduleFailure.class, () -> fence.execute(guarded, queued,
                current -> current.create(owner, once(60), operation, clock(10))));
        assertTrue(db.tables.values().stream().allMatch(Map::isEmpty));
        var fresh = fence.execute(guarded, fence.capture(),
                current -> current.create(owner, once(60), UUID.randomUUID().toString(), clock(10)));
        assertEquals(2, guarded.owned(owner.uid(), fresh.scheduleId).dataEpoch);
    }

    @Test public void notificationBlockedCreationIsAnIdempotentDraftRequiringExplicitActivation() {
        var block = new java.util.concurrent.atomic.AtomicReference<>("NOTIFICATION_CHANNEL_BLOCKED");
        var guarded = new ScheduleStore(db, () -> 1, () -> false, ignored -> { }, block::get);
        String operation = UUID.randomUUID().toString();
        var created = guarded.create(owner, once(60), operation, clock(0));
        var plan = guarded.owned(owner.uid(), created.scheduleId);
        assertEquals(DRAFT, plan.state); assertEquals(BLOCKED, plan.health);
        var gate = new ScheduleAdmissionStore(guarded);
        assertNull(gate.reconcile(clock(10), false, true).desiredDueAt);
        assertThrows(ScheduleFailure.class, () -> guarded.control(owner.uid(), plan.scheduleId, "", plan.revision, RESUME, UUID.randomUUID().toString(), clock(10)));
        block.set("");
        assertEquals(created.sequence, guarded.create(owner, once(60), operation, clock(10)).sequence);
        assertEquals(DRAFT, guarded.owned(owner.uid(), plan.scheduleId).state);
        guarded.control(owner.uid(), plan.scheduleId, "", plan.revision, RESUME, UUID.randomUUID().toString(), clock(10));
        assertEquals(ACTIVE, guarded.owned(owner.uid(), plan.scheduleId).state);
        assertEquals(Long.valueOf(clock(60).wall().toEpochMilli()), gate.reconcile(clock(10), false, true).desiredDueAt);
    }
    @Test public void activeNotificationRevocationRemovesArmAndRestoresOnlyUnconsumedOccurrence() {
        var block = new java.util.concurrent.atomic.AtomicReference<>("");
        var guarded = new ScheduleStore(db, () -> 1, () -> false, ignored -> { }, block::get);
        var gate = new ScheduleAdmissionStore(guarded);
        String id = guarded.create(owner, once(10), UUID.randomUUID().toString(), clock(0)).scheduleId;
        block.set("NOTIFICATIONS_DISABLED");
        var disarmed = gate.reconcile(clock(10), false, true);
        gate.acknowledgeArm(1, disarmed.armGeneration, "");
        assertNull(disarmed.desiredDueAt); assertEquals(ACTIVE, guarded.owned(owner.uid(), id).state);
        assertEquals(BLOCKED, guarded.owned(owner.uid(), id).health);
        assertFalse(gate.admitOne("admit:" + id, clock(10), 1, 1));
        block.set("");
        var restored = gate.reconcile(clock(20), false, true);
        assertEquals(Long.valueOf(clock(10).wall().toEpochMilli()), restored.desiredDueAt);
        assertTrue(gate.admitOne("admit:" + id, clock(20), clock(20).wall().toEpochMilli(), clock(20).elapsedMillis()));
        assertFalse(gate.admitOne("admit:" + id, clock(20), 1, 1));
    }
    @Test public void operationWriteFailureRollsBackCreationThenSameOperationCanRecover() {
        String operation = UUID.randomUUID().toString(); db.failNext = "insertControl";
        assertThrows(IllegalStateException.class, () -> store.create(owner, once(10), operation, clock(0)));
        assertTrue(db.tables.values().stream().allMatch(Map::isEmpty));
        var created = store.create(owner, once(10), operation, clock(0));
        assertEquals(created.scheduleId, store.create(owner, once(10), operation, clock(1)).scheduleId);
        assertEquals(1, db.tables.get("plans").size());
    }
    @Test public void acceptancePersistenceFailureCannotLeaveReceiptOrLoseDispatch() {
        String id = create(10); admit(id, 10);
        var run = store.dao().occurrence(id, store.dao().definition(id).fixedOccurrenceKey);
        admission.claim("dispatch:" + run.runId, clock(10));
        var port = new DurableRuntimeExecutionPort(store, () -> clock(10).wall().toEpochMilli());
        var auth = new RuntimeExecutionPort.AuthorizationSnapshot(run.authorizationJson, Actor.PASSENGER, VehicleZone.PASSENGER);
        var context = new RuntimeExecutionPort.ExecutionContext("", 1, run.expiresAt);
        long sequence = db.sequence; db.failNext = "insertAcceptance";
        assertThrows(IllegalStateException.class, () -> port.accept(run.runtimeRequestId, run.runId, ScheduleCodec.spec(run.specJson), auth, context));
        assertNull(port.getAcceptance(run.runtimeRequestId)); assertEquals(sequence, db.sequence);
        assertNotNull(store.dao().outbox("dispatch:" + run.runId));
        var accepted = port.accept(run.runtimeRequestId, run.runId, ScheduleCodec.spec(run.specJson), auth, context);
        assertEquals(accepted, port.getAcceptance(run.runtimeRequestId));
    }
    @Test public void acknowledgementFromOldArmCannotOverwriteNewTarget() {
        create(10); var first = admission.reconcile(clock(0), false, true);
        var suspended = admission.reconcile(clock(1), false, false);
        assertNull(suspended.desiredDueAt);
        admission.acknowledgeArm(1, first.armGeneration, "");
        assertEquals(suspended.armGeneration, store.dao().arm().armGeneration);
        assertEquals(PENDING, store.dao().arm().state);
    }
    @Test public void firstBroadcastTimestampSurvivesContinuationRecovery() {
        String id = create(10); admission.reconcile(clock(10), false, true);
        admission.recordReceipt("admit:" + id, clock(10).wall().toEpochMilli(), clock(10).elapsedMillis());
        admission.attempted("admit:" + id, clock(10), "INTERRUPTED");
        admission.recordReceipt("admit:" + id, clock(20).wall().toEpochMilli(), clock(20).elapsedMillis());
        admission.admitOne("admit:" + id, clock(20), clock(20).wall().toEpochMilli(), clock(20).elapsedMillis());
        var run = store.dao().occurrence(id, store.dao().definition(id).fixedOccurrenceKey);
        assertEquals(Long.valueOf(clock(10).wall().toEpochMilli()), run.receivedAt);
        assertEquals(Long.valueOf(clock(20).wall().toEpochMilli()), run.admittedAt);
    }
    @Test public void resetPendingRejectsReadMutationAndAcceptanceEvenBeforeEpochAdvances() {
        String id = create(10); admit(id, 10);
        var pending = new java.util.concurrent.atomic.AtomicBoolean(true);
        var guarded = new ScheduleStore(db, () -> 1, pending::get);
        assertThrows(ScheduleFailure.class, () -> guarded.owned(owner.uid(), id));
        assertThrows(ScheduleFailure.class, () -> guarded.create(owner, once(20), UUID.randomUUID().toString(), clock(11)));
        assertThrows(ScheduleFailure.class, () -> new ScheduleAdmissionStore(guarded).reconcile(clock(11), false, true));
        pending.set(false); assertEquals(id, guarded.owned(owner.uid(), id).scheduleId);
    }
    @Test public void androidUserAndOccupantIdentityCannotBeConfused() {
        assertThrows(IllegalArgumentException.class, () -> new ScheduleIdentity(110001, 0, "owner", "sig", Actor.DRIVER, VehicleZone.DRIVER));
        assertThrows(IllegalArgumentException.class, () -> new ScheduleIdentity(10001, 1, "owner", "sig", Actor.DRIVER, VehicleZone.DRIVER));
        assertThrows(IllegalArgumentException.class, () -> new ScheduleIdentity(10001, 0, "owner", "sig", Actor.PASSENGER, VehicleZone.DRIVER));
    }

    @Test public void repairAdmissionDoesNotInventABroadcastReceipt() {
        String id = create(10); admission.reconcile(clock(15), false, true);
        admission.admitOne("admit:" + id, clock(15), 0, 0);
        var run = store.dao().occurrence(id, store.dao().definition(id).fixedOccurrenceKey);
        assertNull(run.receivedAt); assertNull(run.receivedElapsed); assertEquals("RECONCILED", run.triggerKind);
    }
    @Test public void suppressedSpeechPreservesNotificationFactWithoutClaimingCompleteDelivery() throws Exception {
        var scheduled = once(10);
        var spec = new ScheduleSpec(scheduled.title, scheduled.timing,
                new ScheduleAction(AGENT, "summary", "", 0, "{}", List.of(), false, true), scheduled.graceMillis, scheduled.misfirePolicy);
        String id = store.create(owner, spec, UUID.randomUUID().toString(), clock(0)).scheduleId; admit(id, 10);
        var run = store.dao().occurrence(id, store.dao().definition(id).fixedOccurrenceKey);
        admission.recordDelivery(run.runId, "notification", "DELIVERED", "{\"interruptionFilter\":3}", clock(11));
        assertNull(store.dao().run(run.runId).deliveredAt);
        admission.recordDelivery(run.runId, "speech", "SUPPRESSED_BY_POLICY", "{}", clock(12));
        admission.finish(run.runId, PARTIAL, DELIVERY_PARTIAL, "summary", "SUPPRESSED_BY_POLICY", clock(12), true);
        var retained = store.dao().run(run.runId); assertNull(retained.deliveredAt);
        var facts = new org.json.JSONObject(retained.deliveryFactsJson);
        assertEquals(clock(11).wall().toEpochMilli(), facts.getJSONObject("notification").getLong("deliveredAt"));
        assertEquals("UNKNOWN", facts.getJSONObject("notification").getString("soundStatus"));
        assertFalse(facts.getJSONObject("speech").has("deliveredAt"));
    }

}
