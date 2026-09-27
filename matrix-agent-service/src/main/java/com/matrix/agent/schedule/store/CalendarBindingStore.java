package com.matrix.agent.schedule.store;

import static com.matrix.agent.api.schedule.ScheduleCodes.*;
import static com.matrix.agent.api.common.MatrixErrorCode.*;
import com.matrix.agent.data.schedule.*;
import com.matrix.agent.schedule.domain.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/** Calendar reads finish before entering these short local transactions. */
public final class CalendarBindingStore {
    private final ScheduleStore store;
    public CalendarBindingStore(ScheduleStore store) { this.store = store; }
    public ScheduleBindingEntity bind(ScheduleIdentity owner, long calendar, long event, long original,
            String reminderOwner, CalendarSnapshot source, String operationId, ClockSample now) {
        ScheduleNormalizer.requireUuid(operationId);
        if (calendar <= 0 || event <= 0 || original <= 0 || !(reminderOwner.equals("AGENT") || reminderOwner.equals("BOTH_EXPLICIT")))
            throw new IllegalArgumentException("invalid binding");
        String id = UUID.nameUUIDFromBytes((owner.uid() + ":binding:" + operationId).getBytes(StandardCharsets.UTF_8)).toString();
        return store.database().runInTransaction(() -> {
            long epoch = store.currentEpoch();
            var previous = store.dao().binding(id);
            if (previous != null) {
                if (previous.dataEpoch != epoch || previous.eventId != event || previous.calendarId != calendar
                        || !previous.originalInstanceKey.equals(Long.toString(original)) || !previous.reminderOwner.equals(reminderOwner))
                    throw new ScheduleFailure(IDEMPOTENCY_CONFLICT, "绑定操作编号对应不同请求");
                return previous;
            }
            if (store.dao().bindings(owner.uid()).size() >= 100) throw new ScheduleFailure(OVERLOADED, "最多保存 100 个日历绑定");
            var row = new ScheduleBindingEntity(); row.bindingId = id; row.ownerUid = owner.uid(); row.ownerUserId = 0;
            row.dataEpoch = epoch; row.authority = "com.android.calendar"; row.calendarId = calendar; row.eventId = event;
            row.originalInstanceKey = Long.toString(original); row.reminderOwner = reminderOwner;
            row.sourceRevision = source.encode(); row.state = "VALID"; row.lastVerifiedAt = now.wall().toEpochMilli();
            store.dao().putBinding(row); return row;
        });
    }
    public void invalidateAll(ClockSample clock) {
        store.database().runInTransaction(() -> {
            for (var binding : store.dao().allBindings()) {
                if (binding.dataEpoch != store.currentEpoch()) continue;
                for (var plan : store.dao().calendarDefinitions(binding.bindingId)) {
                    if (plan.state != ACTIVE && store.dao().unresolvedRuns(plan.scheduleId).isEmpty()) continue;
                    var intent = new ScheduleOutboxEntity(); intent.effectId = "calendar:" + plan.scheduleId;
                    intent.kind = "CALENDAR_REFRESH"; intent.scheduleId = plan.scheduleId; intent.dataEpoch = plan.dataEpoch;
                    intent.nextAttemptAt = clock.wall().toEpochMilli(); intent.nextElapsedAt = clock.elapsedMillis(); intent.bootId = clock.bootId();
                    store.dao().putOutbox(intent);
                }
            }
        });
    }
    public ScheduleBindingEntity owned(int owner, String id) {
        var row = store.dao().binding(id);
        if (row == null || row.ownerUid != owner || row.dataEpoch != store.currentEpoch()) throw new ScheduleFailure(NOT_FOUND, "日历绑定不存在");
        return row;
    }
    public void refresh(String bindingId, long epoch, CalendarSnapshot source, String state, ClockSample clock) {
        store.database().runInTransaction(() -> {
            if (epoch != store.currentEpoch()) return;
            var binding = store.dao().binding(bindingId);
            if (binding == null || binding.dataEpoch != epoch) return;
            String encoded = source == null ? binding.sourceRevision : source.encode();
            boolean changed = !binding.sourceRevision.equals(encoded) || !binding.state.equals(state);
            binding.sourceRevision = encoded; binding.state = state; binding.lastVerifiedAt = clock.wall().toEpochMilli();
            store.dao().putBinding(binding);
            for (var plan : store.dao().calendarDefinitions(bindingId)) {
                var run = store.dao().occurrence(plan.scheduleId, plan.fixedOccurrenceKey);
                if (changed) {
                    plan.revision++; plan.registrationGeneration++; plan.updatedAt = binding.lastVerifiedAt;
                    plan.effectiveFrom = binding.lastVerifiedAt;
                    store.event(plan, run == null ? "" : run.runId, "CALENDAR_RECONCILED", state, binding.lastVerifiedAt);
                }
                if (!state.equals("VALID")) {
                    plan.nextDueAt = plan.nextElapsedAt = null; plan.health = BLOCKED; plan.reason = "CALENDAR_" + state;
                    store.dao().deleteScheduleOutbox(plan.scheduleId);
                    if (run != null && !terminalRun(run.state)) {
                        run.state = run.dispatchClaimed ? CANCEL_REQUESTED : CANCELLED;
                        run.reason = "CALENDAR_" + state; run.updatedAt = binding.lastVerifiedAt;
                        if (!run.dispatchClaimed) run.completedAt = binding.lastVerifiedAt;
                        store.dao().updateRun(run);
                    }
                } else if (run != null && (run.dispatchClaimed || terminalRun(run.state))) {
                    // Consumed source identities are never resurrected by an edit.
                    plan.nextDueAt = plan.nextElapsedAt = null;
                    if (changed && !terminalRun(run.state)) {
                        run.state = CANCEL_REQUESTED; run.reason = "CALENDAR_SOURCE_CHANGED"; store.dao().updateRun(run);
                    }
                } else if (plan.state == ACTIVE || run != null && plan.state == COMPLETED) {
                    plan.health = PENDING; plan.reason = ""; plan.state = ACTIVE;
                    store.setNext(plan, Instant.MIN, clock);
                    if (run != null && changed) {
                        store.dao().deleteRunOutbox(run.runId);
                        store.event(plan, run.runId, "TIMING_REPLACED", "CALENDAR_MOVED", binding.lastVerifiedAt);
                        run.state = WAITING_TRIGGER; run.timingRevision++; run.dispatchGeneration++;
                        run.scheduledAt = plan.nextDueAt; run.expiresAt = run.scheduledAt + ScheduleCodec.spec(run.specJson).graceMillis;
                        run.receivedAt = run.admittedAt = run.receivedElapsed = null;
                        run.updatedAt = binding.lastVerifiedAt; store.dao().updateRun(run);
                    }
                }
                store.dao().updateDefinition(plan);
            }
        });
    }
}
