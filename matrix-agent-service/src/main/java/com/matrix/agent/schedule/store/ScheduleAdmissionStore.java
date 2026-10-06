package com.matrix.agent.schedule.store;

import static com.matrix.agent.api.schedule.ScheduleCodes.*;

import com.matrix.agent.api.schedule.ScheduleSpec;
import com.matrix.agent.data.schedule.*;
import com.matrix.agent.schedule.domain.*;

import java.time.Instant;
import java.util.Optional;

/** Bounded durable reconciliation. Every candidate has its own persisted retry history. */
public final class ScheduleAdmissionStore {
    public static final String ADMIT = "ADMIT", DISPATCH = "DISPATCH";
    private final ScheduleStore store;
    private final ScheduleDao dao;

    public ScheduleAdmissionStore(ScheduleStore store) { this.store = store; dao = store.dao(); }

    /** No business effects: reconstruct clocks, persist due intents, compute the one desired alarm. */
    public ScheduleArmEntity reconcile(ClockSample clock, boolean timeChanged, boolean userReady) {
        return store.database().runInTransaction(() -> {
            long epoch = store.currentEpoch(), now = clock.wall().toEpochMilli();
            for (ScheduleDefinitionEntity plan : dao.activeDefinitions()) {
                if (plan.dataEpoch != epoch) continue;
                TimeRule rule = ScheduleCodec.rule(plan.timeRuleJson);
                if (timeChanged || !plan.bootId.equals(clock.bootId())) {
                    Instant floor = Instant.ofEpochMilli(Math.max(plan.effectiveFrom,
                            plan.processedAt == null ? Long.MIN_VALUE : plan.processedAt));
                    store.setNext(plan, ScheduleStore.isFixed(rule) ? Instant.MIN : floor, clock);
                    plan.registrationGeneration++;
                    ScheduleOutboxEntity continuation = dao.outbox("admit:" + plan.scheduleId);
                    if (continuation != null) {
                        if (plan.nextDueAt == null || plan.nextDueAt > now) {
                            dao.deleteOutbox(continuation.effectId);
                        } else {
                            continuation.generation = plan.registrationGeneration;
                            if (continuation.attempt == 0) continuation.nextAttemptAt = plan.nextDueAt;
                            else if (continuation.bootId.equals(clock.bootId())) {
                                continuation.nextAttemptAt = now + Math.max(0, continuation.nextElapsedAt - clock.elapsedMillis());
                            }
                            continuation.bootId = clock.bootId();
                            continuation.nextElapsedAt = clock.elapsedAt(Instant.ofEpochMilli(continuation.nextAttemptAt));
                            dao.putOutbox(continuation);
                        }
                    }
                }
                if (plan.nextDueAt != null && plan.nextDueAt <= now) {
                    String effectId = "admit:" + plan.scheduleId;
                    ScheduleOutboxEntity intent = dao.outbox(effectId);
                    if (intent == null) {
                        intent = new ScheduleOutboxEntity();
                        intent.effectId = effectId; intent.kind = ADMIT; intent.scheduleId = plan.scheduleId;
                        intent.dataEpoch = epoch; intent.generation = plan.registrationGeneration;
                        intent.nextAttemptAt = plan.nextDueAt; intent.cutoffAt = now;
                        intent.bootId = clock.bootId(); intent.nextElapsedAt = clock.elapsedAt(Instant.ofEpochMilli(intent.nextAttemptAt));
                        dao.putOutbox(intent);
                    }
                }
                dao.updateDefinition(plan);
            }
            return desiredArm(clock, userReady);
        });
    }

    /** Caller arms continuation before processing pages, so death between pages retains a wake source. */
    public boolean admitOne(String effectId, ClockSample clock, long receivedAt, long receivedElapsed) {
        return admitOne(effectId, clock, receivedAt, receivedElapsed, false);
    }
    public boolean admitCalendar(String effectId, ClockSample clock, long receivedAt, long receivedElapsed) {
        return admitOne(effectId, clock, receivedAt, receivedElapsed, true);
    }
    private boolean admitOne(String effectId, ClockSample clock, long receivedAt, long receivedElapsed, boolean calendarVerified) {
        return store.database().runInTransaction(() -> {
            long epoch = store.currentEpoch(), now = clock.wall().toEpochMilli();
            ScheduleOutboxEntity intent = dao.outbox(effectId);
            if (intent == null || !ADMIT.equals(intent.kind) || intent.dataEpoch != epoch) return false;
            ScheduleDefinitionEntity plan = dao.definition(intent.scheduleId);
            if (plan == null || plan.state != ACTIVE || plan.dataEpoch != epoch || plan.nextDueAt == null) {
                dao.deleteOutbox(effectId); return false;
            }
            if (!store.notificationBlock(plan).isEmpty()) return false;
            TimeRule rule = ScheduleCodec.rule(plan.timeRuleJson);
            Occurrence occurrence;
            if (plan.nextDueAt > now) { dao.deleteOutbox(effectId); return false; }
            if (rule instanceof TimeRule.CalendarOffset calendar) {
                if (!calendarVerified) return false;
                var binding = new CalendarBindingStore(store).owned(plan.ownerUid, calendar.bindingId());
                if (!binding.state.equals("VALID") || now - binding.lastVerifiedAt > 5_000) return false;
            }
            if (rule instanceof TimeRule.Recurring recurring) {
                // Latest-only coalescing is bounded even if the device has been off for years.
                Optional<Occurrence> latest = OccurrenceCalculator.latestDue(recurring, clock.wall(), plan.ruleGeneration, clock);
                if (latest.isEmpty() || latest.get().scheduledAt().toEpochMilli() < plan.nextDueAt) {
                    dao.deleteOutbox(effectId); return false;
                }
                occurrence = latest.get();
                if (occurrence.scheduledAt().toEpochMilli() > plan.nextDueAt) {
                    store.event(plan, "", "MISFIRE_COALESCED", "OLDER_SLOTS_OMITTED", now);
                }
            } else {
                occurrence = new Occurrence(plan.fixedOccurrenceKey, Instant.ofEpochMilli(plan.nextDueAt),
                        plan.nextElapsedAt == null ? clock.elapsedMillis() : plan.nextElapsedAt);
            }
            ScheduleSpec spec = ScheduleCodec.spec(plan.specJson);
            ScheduleRunEntity run = dao.occurrence(plan.scheduleId, occurrence.key());
            boolean fresh = run == null;
            if (fresh) {
                run = new ScheduleRunEntity(); run.runId = ScheduleStore.uuid();
                run.scheduleId = plan.scheduleId; run.occurrenceKey = occurrence.key();
                run.ownerUid = plan.ownerUid; run.dataEpoch = epoch;
                run.runtimeRequestId = ScheduleStore.uuid(); run.timingRevision = run.dispatchGeneration = 1;
            }
            if (fresh || run.state == WAITING_TRIGGER) {
                if (fresh) {
                    run.definitionRevision = plan.revision; run.actor = plan.actor; run.zone = plan.zone;
                    run.title = spec.title; run.specJson = plan.specJson; run.authorizationJson = authorization(plan);
                } else spec = ScheduleCodec.spec(run.specJson);
                run.scheduledAt = occurrence.scheduledAt().toEpochMilli();
                run.expiresAt = Math.addExact(run.scheduledAt, spec.graceMillis);
                run.receivedAt = receivedAt > 0 ? receivedAt : null; run.receivedElapsed = receivedAt > 0 ? receivedElapsed : null;
                if (!intent.cursor.isEmpty()) {
                    try {
                        var receipt = new org.json.JSONObject(intent.cursor);
                        run.receivedAt = receipt.getLong("receivedAt"); run.receivedElapsed = receipt.getLong("receivedElapsed");
                    } catch (org.json.JSONException invalid) { throw new IllegalStateException("invalid persisted receipt", invalid); }
                }
                run.admittedAt = now; run.bootId = clock.bootId(); run.updatedAt = now;
                run.triggerKind = run.receivedAt == null ? "RECONCILED" : "SCHEDULED";
                if (fresh) run.requestHash = ScheduleCodec.digest(run.runtimeRequestId, run.specJson, run.authorizationJson,
                        Long.toString(run.dataEpoch), run.actor, run.zone);
                run.deliveryStatus = DELIVERY_PENDING;
                // A normal receiver may have small scheduling latency. SKIP rejects missed historical slots,
                // not the milliseconds consumed by an on-time broadcast and persistence admission.
                boolean skip = now > run.expiresAt || (spec.misfirePolicy == SKIP && now - run.scheduledAt > 60_000);
                boolean overlap = false;
                for (ScheduleRunEntity other : dao.unresolvedRuns(plan.scheduleId)) {
                    // Cancellation is a request, not proof that an in-flight external write stopped.
                    if (!other.runId.equals(run.runId) && other.state != WAITING_TRIGGER) { overlap = true; break; }
                }
                run.state = skip ? MISSED : overlap ? SKIPPED : QUEUED;
                if (skip) { run.reason = "OUTSIDE_ALLOWED_WINDOW"; run.completedAt = now; run.deliveryStatus = DELIVERY_NOT_REQUIRED; }
                if (overlap && !skip) { run.reason = "PREVIOUS_RUN_UNRESOLVED"; run.completedAt = now; run.deliveryStatus = DELIVERY_NOT_REQUIRED; }
                if (fresh) dao.insertRun(run); else dao.updateRun(run);
                if (!skip && !overlap) {
                    ScheduleOutboxEntity dispatch = new ScheduleOutboxEntity();
                    dispatch.effectId = "dispatch:" + run.runId; dispatch.kind = DISPATCH;
                    dispatch.scheduleId = plan.scheduleId; dispatch.runId = run.runId;
                    dispatch.dataEpoch = epoch; dispatch.generation = run.dispatchGeneration;
                    dispatch.nextAttemptAt = run.scheduledAt; dispatch.nextElapsedAt = occurrence.elapsedDeadline();
                    dispatch.bootId = clock.bootId(); dispatch.cutoffAt = run.expiresAt;
                    dao.putOutbox(dispatch);
                }
                store.event(plan, run.runId, skip ? "MISSED" : overlap ? "SKIPPED" : "ADMITTED", run.reason, now);
            }
            plan.processedAt = occurrence.scheduledAt().toEpochMilli(); plan.processedKey = occurrence.key();
            if (rule instanceof TimeRule.CalendarOffset) plan.nextDueAt = plan.nextElapsedAt = null;
            else store.setNext(plan, clock.wall(), clock);
            if (plan.nextDueAt == null) plan.state = COMPLETED;
            plan.updatedAt = now; dao.updateDefinition(plan); dao.deleteOutbox(effectId);
            return true;
        });
    }

    /** Delivery consumes a one-shot OS registration; boot/permission/clock changes also invalidate it. */
    public void invalidateArm() {
        store.database().runInTransaction(() -> {
            var arm = dao.arm();
            if (arm != null && arm.dataEpoch == store.currentEpoch()) {
                arm.state = PENDING;
                dao.putArm(arm);
            }
        });
    }

    public ScheduleArmEntity desiredArm(ClockSample clock, boolean userReady) {
        long epoch = store.currentEpoch(), now = clock.wall().toEpochMilli();
        Long target = null;
        if (userReady) {
            for (ScheduleDefinitionEntity plan : dao.activeDefinitions()) {
                if (plan.dataEpoch == epoch && store.notificationBlock(plan).isEmpty() && plan.nextDueAt != null && plan.nextDueAt > now) {
                    target = ContinuationPolicy.armTarget(target, plan.nextDueAt);
                }
            }
            for (ScheduleOutboxEntity intent : dao.pendingOutbox(epoch)) {
                var plan = dao.definition(intent.scheduleId);
                if (ADMIT.equals(intent.kind) && plan != null && !store.notificationBlock(plan).isEmpty()) continue;
                target = ContinuationPolicy.armTarget(target, intent.nextAttemptAt);
            }
        }
        ScheduleArmEntity arm = dao.arm();
        if (arm != null && arm.dataEpoch == epoch && arm.bootId.equals(clock.bootId())
                && java.util.Objects.equals(arm.desiredDueAt, target) && arm.state == ARMED) return arm;
        if (arm == null) arm = new ScheduleArmEntity();
        arm.dataEpoch = epoch; arm.armGeneration++; arm.desiredDueAt = target;
        arm.elapsedDueAt = target == null ? null : clock.elapsedAt(Instant.ofEpochMilli(target));
        arm.bootId = clock.bootId(); arm.state = PENDING; dao.putArm(arm);
        return arm;
    }

    public void acknowledgeArm(long epoch, long generation, String failure) {
        store.database().runInTransaction(() -> {
            if (store.currentEpoch() != epoch) return;
            ScheduleArmEntity arm = dao.arm();
            if (arm == null || arm.armGeneration != generation) return;
            arm.appliedGeneration = generation; arm.state = failure.isEmpty() ? ARMED : BLOCKED;
            arm.lastError = failure; dao.putArm(arm);
            for (ScheduleDefinitionEntity plan : dao.activeDefinitions()) {
                if (plan.health == BLOCKED && plan.reason.startsWith("CALENDAR_")) continue;
                String block = failure.isEmpty() ? store.notificationBlock(plan) : failure;
                int health = block.isEmpty() ? arm.state : BLOCKED;
                if (plan.health != health || !plan.reason.equals(block)) {
                    plan.health = health; plan.reason = block; dao.updateDefinition(plan);
                    store.event(plan, "", "REGISTRATION_CHANGED", block, System.currentTimeMillis());
                }
            }
        });
    }

    /** Preserve the receiver's timestamp across a calendar verification service handoff. */
    public void recordReceipt(String effectId, long wall, long elapsed) {
        store.database().runInTransaction(() -> {
            var intent = dao.outbox(effectId);
            if (intent == null || intent.dataEpoch != store.currentEpoch() || !intent.cursor.isEmpty()) return;
            try { intent.cursor = new org.json.JSONObject().put("receivedAt", wall).put("receivedElapsed", elapsed).toString(); }
            catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
            dao.putOutbox(intent);
        });
    }
    /** Advance this candidate before attempting an effect, never postpone untouched siblings. */
    public void attempted(String effectId, ClockSample clock, String reason) {
        store.database().runInTransaction(() -> {
            store.checkAvailable();
            ScheduleOutboxEntity intent = dao.outbox(effectId);
            if (intent == null || intent.dataEpoch != store.currentEpoch()) return;
            intent.attempt++; intent.lastError = reason;
            intent.nextAttemptAt = ContinuationPolicy.nextAttemptAt(intent.nextAttemptAt, clock.wall().toEpochMilli(), intent.attempt);
            intent.bootId = clock.bootId(); intent.nextElapsedAt = clock.elapsedAt(Instant.ofEpochMilli(intent.nextAttemptAt));
            dao.putOutbox(intent);
        });
    }

    public ScheduleRunEntity claim(String effectId, ClockSample clock) {
        return store.database().runInTransaction(() -> {
            long epoch = store.currentEpoch();
            ScheduleOutboxEntity intent = dao.outbox(effectId);
            if (intent == null || intent.dataEpoch != epoch) return null;
            ScheduleRunEntity run = dao.run(intent.runId);
            if (run == null || run.dispatchGeneration != intent.generation || run.dataEpoch != epoch || run.state != QUEUED) {
                dao.deleteOutbox(effectId); return null;
            }
            if (clock.wall().toEpochMilli() > run.expiresAt) {
                finish(run.runId, MISSED, DELIVERY_NOT_REQUIRED, "", "OUTSIDE_ALLOWED_WINDOW", clock, false);
                return null;
            }
            if (!run.dispatchClaimed && dao.claim(run.runId, run.dispatchGeneration, epoch) != 1) return null;
            run.dispatchClaimed = true;
            if (ScheduleCodec.spec(run.specJson).action.kind == NOTIFICATION && run.startedAt == null) {
                run.startedAt = clock.wall().toEpochMilli(); run.startedElapsed = clock.elapsedMillis();
                dao.updateRun(run);
            }
            return run;
        });
    }

    public void recordDelivery(String id, String channel, String status, String policy, ClockSample clock) {
        store.database().runInTransaction(() -> {
            var run = dao.run(id);
            if (run == null || run.dataEpoch != store.currentEpoch()) return;
            if (!DeliveryFacts.delivered(run.deliveryFactsJson, channel)) {
                run.deliveryFactsJson = DeliveryFacts.record(run.deliveryFactsJson, channel, status, clock.wall().toEpochMilli(), policy);
            }
            boolean weather = com.matrix.agent.schedule.workflow.WeatherWorkflow.ID.equals(
                    ScheduleCodec.spec(run.specJson).action.templateId);
            boolean complete = DeliveryFacts.delivered(run.deliveryFactsJson, weather ? "weather_update" : "notification")
                    && (!ScheduleCodec.spec(run.specJson).action.speakResult || DeliveryFacts.delivered(run.deliveryFactsJson, "speech"));
            if (complete && run.deliveredAt == null) run.deliveredAt = clock.wall().toEpochMilli();
            dao.updateRun(run);
        });
    }

    public void finish(String id, int state, int delivery, String result, String reason, ClockSample clock, boolean delivered) {
        store.database().runInTransaction(() -> {
            long epoch = store.currentEpoch();
            ScheduleRunEntity run = dao.run(id);
            if (run == null || run.dataEpoch != epoch) return;
            if (terminalRun(run.state) && run.deliveryStatus != DELIVERY_PENDING) return;
            run.state = state; run.deliveryStatus = delivery; run.result = result; run.reason = reason;
            run.updatedAt = clock.wall().toEpochMilli();
            if (terminalRun(state) && run.completedAt == null) run.completedAt = run.updatedAt;
            if (delivered && (!ScheduleCodec.spec(run.specJson).action.speakResult || DeliveryFacts.delivered(run.deliveryFactsJson, "speech"))
                    && run.deliveredAt == null) run.deliveredAt = run.updatedAt;
            dao.updateRun(run); dao.deleteRunOutbox(id);
            ScheduleDefinitionEntity plan = dao.definition(run.scheduleId);
            if (plan != null) store.event(plan, id, "RUN_CHANGED", reason, run.updatedAt);
        });
    }

    private static String authorization(ScheduleDefinitionEntity row) {
        try {
            return new org.json.JSONObject().put("uid", row.ownerUid).put("user", row.ownerUserId)
                    .put("package", row.ownerPackage).put("signature", row.signatureDigest)
                    .put("actor", row.actor).put("zone", row.zone).toString();
        } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
    }
}
