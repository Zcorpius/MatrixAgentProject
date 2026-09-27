package com.matrix.agent.schedule.store;

import static com.matrix.agent.api.common.MatrixErrorCode.*;
import static com.matrix.agent.api.schedule.ScheduleCodes.*;

import com.matrix.agent.api.schedule.*;
import com.matrix.agent.data.db.MatrixDatabase;
import com.matrix.agent.data.schedule.*;
import com.matrix.agent.schedule.domain.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Authoritative command transaction boundary. No Android effects or runtime calls occur here. */
public final class ScheduleStore {
    private final MatrixDatabase database;
    private final ScheduleDao dao;
    private final LongSupplier epoch;
    private final BooleanSupplier resetPending;
    private final ScheduleNormalizer normalizer = new ScheduleNormalizer();
    private final java.util.function.Consumer<ScheduleAction> actionValidator;
    private final java.util.function.Supplier<String> reminderBlock;

    public ScheduleStore(MatrixDatabase database, LongSupplier epoch, BooleanSupplier resetPending) {
        this(database, epoch, resetPending, action -> { });
    }
    public ScheduleStore(MatrixDatabase database, LongSupplier epoch, BooleanSupplier resetPending,
            java.util.function.Consumer<ScheduleAction> actionValidator) {
        this(database, epoch, resetPending, actionValidator, () -> "");
    }
    public ScheduleStore(MatrixDatabase database, LongSupplier epoch, BooleanSupplier resetPending,
            java.util.function.Consumer<ScheduleAction> actionValidator, java.util.function.Supplier<String> reminderBlock) {
        this.database = database;
        this.dao = database.scheduleDao();
        this.epoch = epoch;
        this.resetPending = resetPending;
        this.actionValidator = actionValidator;
        this.reminderBlock = reminderBlock;
    }

    public String notificationBlock(ScheduleDefinitionEntity plan) {
        return ScheduleCodec.spec(plan.specJson).action.kind == NOTIFICATION ? reminderBlock.get() : "";
    }

    public void validateAction(ScheduleAction action) { actionValidator.accept(action); }

    public MatrixDatabase database() { return database; }
    public ScheduleDao dao() { return dao; }
    public long currentEpoch() { checkAvailable(); return epoch.getAsLong(); }
    public void checkAvailable() {
        if (resetPending.getAsBoolean() || !database.isOpen()) {
            throw new ScheduleFailure(PERSISTENCE_UNAVAILABLE, "计划存储正在恢复或清除");
        }
    }

    public ScheduleMutation create(ScheduleIdentity identity, ScheduleSpec spec, String operationId,
            ClockSample clock) {
        ScheduleNormalizer.requireUuid(operationId);
        // Hash caller intent before resolving relative times. A replay must not move a delay's origin.
        String hash = ScheduleCodec.digest("create", ScheduleCodec.spec(spec), identity.toString());
        return database.runInTransaction(() -> {
            checkAvailable();
            ScheduleMutation replay = replay(identity.uid(), operationId, hash);
            if (replay != null) return replay;
            var normalized = normalizer.normalize(spec, clock);
            validateAction(normalized.spec().action);
            ScheduleDefinitionEntity row = new ScheduleDefinitionEntity();
            row.scheduleId = uuid(); row.ownerUid = identity.uid(); row.ownerUserId = 0;
            row.ownerPackage = identity.packageName(); row.signatureDigest = identity.signatureDigest();
            row.actor = identity.actor().name(); row.zone = identity.zone().wireValue();
            row.revision = row.ruleGeneration = row.registrationGeneration = 1;
            row.fixedOccurrenceKey = uuid(); row.specJson = ScheduleCodec.spec(normalized.spec());
            row.timeRuleJson = ScheduleCodec.rule(normalized.rule());
            row.state = ACTIVE; row.health = PENDING; row.dataEpoch = currentEpoch();
            row.effectiveFrom = row.createdAt = row.updatedAt = clock.wall().toEpochMilli();
            setNext(row, clock.wall(), clock);
            if (row.nextDueAt == null && normalized.rule() instanceof TimeRule.CalendarOffset)
                throw new ScheduleFailure(INVALID_ARGUMENT, "所选日历实例的触发时刻已过去或不可用");
            String blocked = notificationBlock(row);
            if (!blocked.isEmpty()) { row.state = DRAFT; row.health = BLOCKED; row.reason = blocked; }
            else if (dao.activeCount(0) >= 100) throw new ScheduleFailure(OVERLOADED, "最多启用 100 个计划");
            dao.insertDefinition(row);
            long sequence = event(row, "", "CREATED", "", clock.wall().toEpochMilli());
            return remember(identity.uid(), operationId, hash, row, "", sequence, row.state == DRAFT ? "通知不可用，计划已保存为草稿；恢复权限后请启用" : "计划已保存，正在安排");
        });
    }

    public ScheduleMutation update(ScheduleIdentity identity, String id, long revision,
            ScheduleSpec input, String operationId, ClockSample clock) {
        ScheduleNormalizer.requireUuid(operationId);
        String hash = ScheduleCodec.digest("update", id, Long.toString(revision), ScheduleCodec.spec(input));
        return database.runInTransaction(() -> {
            checkAvailable();
            ScheduleMutation replay = replay(identity.uid(), operationId, hash);
            if (replay != null) return replay;
            ScheduleDefinitionEntity row = owned(identity.uid(), id);
            requireRevision(row, revision);
            if (row.state == DELETED) throw new ScheduleFailure(INVALID_STATE, "计划已删除");
            var normalized = normalizer.normalizeForUpdate(input, clock);
            validateAction(normalized.spec().action);
            ScheduleSpec old = ScheduleCodec.spec(row.specJson);
            // Compare normalized timing independently of title, delivery and execution content.
            String oldTiming = timingHash(old), newTiming = timingHash(normalized.spec());
            boolean changed = !oldTiming.equals(newTiming);
            boolean fixed = isFixed(ScheduleCodec.rule(row.timeRuleJson));
            if (changed && fixed != isFixed(normalized.rule())) {
                throw new ScheduleFailure(INVALID_ARGUMENT, "一次性与周期性规则之间请新建计划");
            }
            ScheduleRunEntity occurrence = fixed ? dao.occurrence(id, row.fixedOccurrenceKey) : null;
            if (changed && fixed && !row.processedKey.isEmpty() && (occurrence == null || occurrence.dispatchClaimed || terminalRun(occurrence.state)))
                throw new ScheduleFailure(TOO_LATE, "本次发生已消费；请新建计划");
            if (changed && occurrence != null && (occurrence.dispatchClaimed
                    || terminalRun(occurrence.state))) {
                throw new ScheduleFailure(TOO_LATE, "本次发生已消费；请新建计划");
            }
            if (changed) {
                // Enforce future timing on edits too, while allowing metadata edits after completion.
                normalized = normalizer.normalize(input, clock);
                row.timeRuleJson = ScheduleCodec.rule(normalized.rule());
                if (!fixed) row.ruleGeneration++;
                row.effectiveFrom = clock.wall().toEpochMilli();
                row.processedAt = null; row.processedKey = "";
            }
            row.revision++; row.registrationGeneration++; row.updatedAt = clock.wall().toEpochMilli();
            row.specJson = ScheduleCodec.spec(normalized.spec());
            row.health = PENDING; row.reason = "";
            if (changed) {
                if (row.state == COMPLETED) {
                    if (dao.activeCount(0) >= 100) throw new ScheduleFailure(OVERLOADED, "启用计划数量已达上限");
                    row.state = ACTIVE;
                }
                dao.deleteScheduleOutbox(id);
                setNext(row, clock.wall(), clock);
                if (occurrence != null) {
                    // Reuse both identities under the unconditional occurrence unique index.
                    event(row, occurrence.runId, "TIMING_REPLACED", "", row.updatedAt);
                    occurrence.timingRevision++; occurrence.dispatchGeneration++;
                    occurrence.definitionRevision = row.revision;
                    occurrence.scheduledAt = row.nextDueAt;
                    occurrence.expiresAt = Math.addExact(occurrence.scheduledAt, normalized.spec().graceMillis);
                    occurrence.state = WAITING_TRIGGER; occurrence.receivedAt = occurrence.admittedAt = null;
                    occurrence.receivedElapsed = null; occurrence.updatedAt = row.updatedAt;
                    occurrence.reason = "";
                    dao.updateRun(occurrence);
                    event(row, occurrence.runId, "RESCHEDULED", "", row.updatedAt);
                }
            }
            for (ScheduleRunEntity pending : dao.unresolvedRuns(id)) {
                if (pending.dispatchClaimed || (changed && fixed && occurrence != null
                        && pending.runId.equals(occurrence.runId))) continue;
                pending.state = CANCELLED; pending.reason = "DEFINITION_CHANGED";
                pending.completedAt = row.updatedAt; pending.updatedAt = row.updatedAt;
                dao.deleteRunOutbox(pending.runId); dao.updateRun(pending);
                event(row, pending.runId, "CANCELLED", pending.reason, row.updatedAt);
            }
            dao.updateDefinition(row);
            long sequence = event(row, "", "UPDATED", "", row.updatedAt);
            return remember(identity.uid(), operationId, hash, row, "", sequence, "计划已更新");
        });
    }

    public ScheduleMutation control(int owner, String id, String runId, long revision, int command,
            String operationId, ClockSample clock) {
        ScheduleNormalizer.requireUuid(operationId);
        String hash = ScheduleCodec.digest("control", id, runId, Long.toString(revision), Integer.toString(command));
        return database.runInTransaction(() -> {
            checkAvailable();
            ScheduleMutation replay = replay(owner, operationId, hash);
            if (replay != null) return replay;
            ScheduleDefinitionEntity row = owned(owner, id);
            requireRevision(row, revision);
            long now = clock.wall().toEpochMilli();
            switch (command) {
                case PAUSE, PAUSE_AND_CANCEL, DELETE -> {
                    row.state = command == DELETE ? DELETED : PAUSED;
                    row.health = PENDING;
                    if (command != PAUSE) dao.deleteScheduleOutbox(id);
                    for (ScheduleRunEntity run : dao.unresolvedRuns(id)) {
                        if (!run.dispatchClaimed || command != PAUSE) cancel(row, run, now);
                    }
                    dao.deleteOutbox("admit:" + id);
                }
                case RESUME -> {
                    if (row.state != PAUSED && row.state != DRAFT) throw new ScheduleFailure(INVALID_STATE, "只有草稿或暂停的计划可以启用");
                    String blocked = notificationBlock(row);
                    if (!blocked.isEmpty()) throw new ScheduleFailure(PERMISSION_DENIED, blocked);
                    if (dao.activeCount(0) >= 100) throw new ScheduleFailure(OVERLOADED, "启用计划数量已达上限");
                    row.state = ACTIVE; row.health = PENDING; row.reason = "";
                    // Resume does not manufacture past occurrences or reuse consumed fixed slots.
                    setNext(row, clock.wall(), clock);
                    if (row.nextDueAt == null) row.state = COMPLETED;
                }
                case SKIP_NEXT -> {
                    if (row.state != ACTIVE || row.nextDueAt == null) throw new ScheduleFailure(INVALID_STATE, "没有可跳过的发生");
                    Instant skipped = Instant.ofEpochMilli(row.nextDueAt);
                    row.processedAt = row.nextDueAt;
                    if (isFixed(ScheduleCodec.rule(row.timeRuleJson))) row.processedKey = row.fixedOccurrenceKey;
                    if (ScheduleCodec.rule(row.timeRuleJson) instanceof TimeRule.CalendarOffset) row.nextDueAt = row.nextElapsedAt = null;
                    else setNext(row, skipped, clock);
                    if (row.nextDueAt == null) row.state = COMPLETED;
                    dao.deleteOutbox("admit:" + id);
                }
                case CANCEL_RUN -> {
                    ScheduleRunEntity run = ownedRun(owner, runId);
                    if (!run.scheduleId.equals(id)) throw new ScheduleFailure(NOT_FOUND, "运行不存在");
                    cancel(row, run, now);
                }
                default -> throw new ScheduleFailure(INVALID_ARGUMENT, "未知控制命令");
            }
            row.revision++; row.registrationGeneration++; row.updatedAt = now;
            dao.updateDefinition(row);
            long sequence = event(row, runId == null ? "" : runId, "CONTROLLED", Integer.toString(command), now);
            return remember(owner, operationId, hash, row, runId == null ? "" : runId, sequence, "操作已受理");
        });
    }

    private void cancel(ScheduleDefinitionEntity plan, ScheduleRunEntity run, long now) {
        if (terminalRun(run.state)) {
            if (run.deliveryStatus == DELIVERY_PENDING) {
                run.deliveryStatus = DELIVERY_NOT_REQUIRED; run.reason = "USER_CANCELLED_DELIVERY"; run.updatedAt = now;
                dao.deleteRunOutbox(run.runId); dao.updateRun(run); event(plan, run.runId, "DELIVERY_CANCELLED", "", now);
            }
            return;
        }
        run.state = run.dispatchClaimed ? CANCEL_REQUESTED : CANCELLED;
        run.reason = "USER_CANCELLED"; run.updatedAt = now;
        if (!run.dispatchClaimed) { run.completedAt = now; run.deliveryStatus = DELIVERY_NOT_REQUIRED; }
        dao.deleteRunOutbox(run.runId); dao.updateRun(run);
        if (run.dispatchClaimed) {
            var recovery = new ScheduleOutboxEntity(); recovery.effectId = "execute:" + run.runId; recovery.kind = "EXECUTE";
            recovery.runId = run.runId; recovery.scheduleId = run.scheduleId; recovery.dataEpoch = run.dataEpoch;
            recovery.nextAttemptAt = now; recovery.cutoffAt = run.expiresAt; dao.putOutbox(recovery);
        }
        event(plan, run.runId, "CANCEL_REQUESTED", "", now);
    }

    public ScheduleDefinitionEntity owned(int owner, String id) {
        checkAvailable();
        ScheduleDefinitionEntity row = dao.definition(id);
        if (row == null || row.ownerUid != owner || row.dataEpoch != currentEpoch()) {
            throw new ScheduleFailure(NOT_FOUND, "计划不存在");
        }
        return row;
    }

    public ScheduleRunEntity ownedRun(int owner, String id) {
        checkAvailable();
        ScheduleRunEntity row = dao.run(id);
        if (row == null || row.ownerUid != owner || row.dataEpoch != currentEpoch()) {
            throw new ScheduleFailure(NOT_FOUND, "运行不存在");
        }
        return row;
    }

    public long event(ScheduleDefinitionEntity plan, String run, String kind, String code, long now) {
        ScheduleEventEntity event = new ScheduleEventEntity();
        event.ownerUid = plan.ownerUid; event.scheduleId = plan.scheduleId; event.runId = run;
        event.kind = kind; event.safeCode = code; event.createdAt = now;
        try {
            var facts = new org.json.JSONObject().put("revision", plan.revision).put("ruleGeneration", plan.ruleGeneration);
            var occurrence = run.isEmpty() ? null : dao.run(run);
            if (occurrence != null) facts.put("timingRevision", occurrence.timingRevision).put("scheduledAt", occurrence.scheduledAt)
                    .put("receivedAt", occurrence.receivedAt).put("admittedAt", occurrence.admittedAt);
            event.timingFactsJson = facts.toString();
        } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
        return dao.insertEvent(event);
    }

    public void setNext(ScheduleDefinitionEntity row, Instant after, ClockSample clock) {
        TimeRule rule = ScheduleCodec.rule(row.timeRuleJson);
        if (isFixed(rule) && !row.processedKey.isEmpty() && dao.occurrence(row.scheduleId, row.fixedOccurrenceKey) == null) {
            row.nextDueAt = row.nextElapsedAt = null; return;
        }
        if (isFixed(rule) && dao.occurrence(row.scheduleId, row.fixedOccurrenceKey) != null) {
            ScheduleRunEntity previous = dao.occurrence(row.scheduleId, row.fixedOccurrenceKey);
            if (previous.dispatchClaimed || terminalRun(previous.state)) {
                row.nextDueAt = row.nextElapsedAt = null; return;
            }
        }
        if (rule instanceof TimeRule.CalendarOffset calendar) {
            var binding = new CalendarBindingStore(this).owned(row.ownerUid, calendar.bindingId());
            if (!binding.state.equals("VALID")) {
                row.nextDueAt = row.nextElapsedAt = null; row.health = BLOCKED; row.reason = "CALENDAR_" + binding.state;
                return;
            }
            var source = CalendarSnapshot.decode(binding.sourceRevision);
            if (source.allDay()) throw new ScheduleFailure(INVALID_ARGUMENT, "全天事件需先明确提醒时刻，请使用指定日期计划");
            row.fixedOccurrenceKey = "calendar:" + binding.bindingId + ":" + binding.eventId + ":" + binding.originalInstanceKey;
            if (dao.occurrence(row.scheduleId, row.fixedOccurrenceKey) != null) {
                var previous = dao.occurrence(row.scheduleId, row.fixedOccurrenceKey);
                if (previous.dispatchClaimed || terminalRun(previous.state)) { row.nextDueAt = row.nextElapsedAt = null; return; }
            }
            long due = source.startMillis() - calendar.offsetMillis();
            if (!Instant.ofEpochMilli(due).isAfter(after)) { row.nextDueAt = row.nextElapsedAt = null; return; }
            row.nextDueAt = due; row.nextElapsedAt = clock.elapsedAt(Instant.ofEpochMilli(due)); row.bootId = clock.bootId();
            return;
        }
        Optional<Occurrence> candidate = OccurrenceCalculator.next(rule, after,
                row.ruleGeneration, row.fixedOccurrenceKey, clock);
        row.nextDueAt = candidate.map(o -> o.scheduledAt().toEpochMilli()).orElse(null);
        row.nextElapsedAt = candidate.map(Occurrence::elapsedDeadline).orElse(null);
        row.bootId = clock.bootId();
    }

    private ScheduleMutation replay(int owner, String operation, String hash) {
        ScheduleControlEntity record = dao.control(owner, operation);
        if (record == null) return null;
        if (!record.requestHash.equals(hash)) throw new ScheduleFailure(IDEMPOTENCY_CONFLICT, "操作编号已用于不同请求");
        return new ScheduleMutation(record.code, operation, record.scheduleId, record.runId,
                record.revision, record.sequence, record.message);
    }

    private ScheduleMutation remember(int owner, String operation, String hash, ScheduleDefinitionEntity plan,
            String run, long sequence, String message) {
        ScheduleControlEntity record = new ScheduleControlEntity();
        record.ownerUid = owner; record.operationId = operation; record.requestHash = hash;
        record.code = SUCCESS; record.scheduleId = plan.scheduleId; record.runId = run;
        record.revision = plan.revision; record.sequence = sequence; record.message = message;
        record.createdAt = plan.updatedAt; dao.insertControl(record);
        return new ScheduleMutation(SUCCESS, operation, plan.scheduleId, run, plan.revision, sequence, message);
    }

    private static String timingHash(ScheduleSpec spec) {
        return ScheduleCodec.spec(new ScheduleSpec("timing", spec.timing,
                new ScheduleAction(NOTIFICATION, "timing", "", 0, "{}", List.of(), false, false), 0, SKIP));
    }
    private static void requireRevision(ScheduleDefinitionEntity row, long expected) {
        if (row.revision != expected) throw new ScheduleFailure(INVALID_STATE, "计划已变化，请刷新后重试");
    }
    public static boolean isFixed(TimeRule rule) { return rule instanceof TimeRule.Once || rule instanceof TimeRule.AfterDelay || rule instanceof TimeRule.CalendarOffset; }
    public static String uuid() { return UUID.randomUUID().toString(); }
}
