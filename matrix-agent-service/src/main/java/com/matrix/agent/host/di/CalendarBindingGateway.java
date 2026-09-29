package com.matrix.agent.host.di;

import static com.matrix.agent.api.common.MatrixErrorCode.*;
import com.matrix.agent.api.schedule.ScheduleCalendarResult;
import com.matrix.agent.data.schedule.*;
import com.matrix.agent.identity.*;
import com.matrix.agent.schedule.domain.*;
import com.matrix.agent.schedule.store.*;
import com.matrix.agent.task.capability.CalendarClockCapabilities;
import com.matrix.agent.task.scheduler.PreparedAutomaticTask;
import com.matrix.agent.task.tool.ToolResult;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import org.json.*;

/** Host-controlled source reads use exactly the same policy and tool boundary as user actions. */
public final class CalendarBindingGateway {
    private final ScheduleGraph schedules;
    private final Supplier<AppContainer> host;
    public CalendarBindingGateway(ScheduleGraph schedules, Supplier<AppContainer> host) { this.schedules = schedules; this.host = host; }
    public ScheduleCalendarResult calendar(ScheduleIdentity owner, String capability, String parameters, String operationId) {
        ScheduleNormalizer.requireUuid(operationId);
        if (!CalendarClockCapabilities.ALL.contains(capability)) throw new IllegalArgumentException("未知日历/时钟能力");
        if (parameters == null || parameters.getBytes(StandardCharsets.UTF_8).length > 8192) throw new IllegalArgumentException("日历参数超过 8 KiB");
        String normalized = ScheduleCodec.canonicalObject(parameters);
        String hash = ScheduleCodec.digest("calendar", capability, normalized);
        boolean write = !Set.of("calendar.list", "calendar.query", "calendar.get", "calendar.instance").contains(capability);
        String requestId = UUID.nameUUIDFromBytes((owner.uid() + ":calendar:" + operationId).getBytes(StandardCharsets.UTF_8)).toString();
        if (write) {
            var previous = schedules.call(store -> store.database().runInTransaction(() -> {
                var prior = store.dao().control(owner.uid(), operationId);
                if (prior != null) {
                    if (!prior.requestHash.equals(hash)) throw new ScheduleFailure(IDEMPOTENCY_CONFLICT, "日历操作编号对应不同请求");
                    return prior;
                }
                var intent = new ScheduleControlEntity(); intent.ownerUid = owner.uid(); intent.operationId = operationId;
                intent.requestHash = hash; intent.code = SERVICE_NOT_READY; intent.message = "PENDING_EXTERNAL_OPERATION";
                intent.createdAt = System.currentTimeMillis(); store.dao().insertControl(intent); return null;
            }));
            if (previous != null) {
                if (previous.code != SERVICE_NOT_READY) return decodeResult(previous.message);
                // Creates carry a deterministic Provider key. Other interrupted writes require an explicit readback.
                if (!capability.equals("calendar.create")) return new ScheduleCalendarResult(INVALID_STATE, "EXECUTION_UNKNOWN", "{}", "上次外部操作回执未落库，请先查询核对");
            }
        }
        var outcome = invoke(owner, capability, arguments(normalized), requestId);
        var result = result(outcome);
        if (write) schedules.call(store -> {
            var row = store.dao().control(owner.uid(), operationId);
            if (row != null && row.requestHash.equals(hash)) { row.code = result.code; row.message = encodeResult(result); store.dao().updateControl(row); }
            return null;
        });
        return result;
    }
    public ScheduleCalendarResult bind(ScheduleIdentity owner, long event, long original, String reminderOwner, String operationId) {
        var outcome = invoke(owner, "calendar.instance", Map.of("eventId", event, "originalStartMillis", original), UUID.randomUUID().toString());
        if (!outcome.isSuccess()) return result(outcome);
        var data = outcome.getObservedState();
        if (!"VALID".equals(data.get("status"))) throw new IllegalArgumentException("日历实例不存在或已取消");
        if ("AGENT".equals(reminderOwner) && data.get("reminders") instanceof List<?> reminders && !reminders.isEmpty())
            throw new IllegalArgumentException("该日程已有原生提醒；请明确选择双提醒，或先在日历中关闭原生提醒");
        CalendarSnapshot snapshot = snapshot(data);
        if (snapshot.allDay()) throw new IllegalArgumentException("全天事件需明确提醒时刻，请使用指定日期计划");
        var row = schedules.call(store -> new CalendarBindingStore(store).bind(owner, ((Number)data.get("calendarId")).longValue(),
                event, original, reminderOwner, snapshot, operationId, schedules.clock().sample()));
        return new ScheduleCalendarResult(SUCCESS, "BOUND", bindingJson(row).toString(), "已绑定指定实例；日历内容不构成动作授权");
    }
    public ScheduleCalendarResult bindings(ScheduleIdentity owner) {
        return schedules.call(store -> {
            JSONArray rows = new JSONArray(); for (var row : store.dao().bindings(owner.uid())) rows.put(bindingJson(row));
            return new ScheduleCalendarResult(SUCCESS, "SUCCESS", object("bindings", rows).toString(), "");
        });
    }
    public void reconcile(String scheduleId) {
        var plan = schedules.call(store -> store.dao().definition(scheduleId));
        if (plan == null || !(ScheduleCodec.rule(plan.timeRuleJson) instanceof TimeRule.CalendarOffset calendar)) return;
        var binding = schedules.call(store -> new CalendarBindingStore(store).owned(plan.ownerUid, calendar.bindingId()));
        var owner = new ScheduleIdentity(plan.ownerUid, 0, plan.ownerPackage, plan.signatureDigest, Actor.valueOf(plan.actor), VehicleZone.parse(plan.zone));
        var outcome = invoke(owner, "calendar.instance", Map.of("eventId", binding.eventId,
                "originalStartMillis", Long.parseLong(binding.originalInstanceKey)), UUID.randomUUID().toString());
        String state = outcome.isSuccess() ? String.valueOf(outcome.getObservedState().get("status")) : "UNAVAILABLE";
        CalendarSnapshot verified = state.equals("VALID") ? snapshot(outcome.getObservedState()) : null;
        if (verified != null && ((Number)outcome.getObservedState().get("calendarId")).longValue() != binding.calendarId) { state = "SOURCE_REPLACED"; verified = null; }
        String finalState = state; CalendarSnapshot source = verified;
        var received = schedules.clock().sample();
        schedules.call(store -> {
            new CalendarBindingStore(store).refresh(binding.bindingId, binding.dataEpoch, source, finalState, received);
            store.dao().deleteOutbox("calendar:" + scheduleId);
            var refreshedPlan = store.dao().definition(scheduleId);
            if (refreshedPlan != null && refreshedPlan.state == com.matrix.agent.api.schedule.ScheduleCodes.ACTIVE) {
                var retry = new ScheduleOutboxEntity(); retry.effectId = "calendar:" + scheduleId; retry.kind = "CALENDAR_REFRESH";
                retry.scheduleId = scheduleId; retry.dataEpoch = binding.dataEpoch; retry.bootId = received.bootId();
                retry.nextAttemptAt = received.wall().toEpochMilli() + 6 * 3_600_000L;
                retry.nextElapsedAt = received.elapsedMillis() + 6 * 3_600_000L; store.dao().putOutbox(retry);
            }
            var admission = new ScheduleAdmissionStore(store);
            admission.reconcile(received, false, schedules.userReady());
            if (finalState.equals("VALID")) {
                long receiptWall = 0, receiptElapsed = 0;
                var intent = store.dao().outbox("admit:" + scheduleId);
                if (intent != null && !intent.cursor.isEmpty()) try {
                    var facts = new JSONObject(intent.cursor); receiptWall = facts.getLong("receivedAt"); receiptElapsed = facts.getLong("receivedElapsed");
                } catch (JSONException invalid) { throw new IllegalStateException("invalid admission receipt", invalid); }
                admission.admitCalendar("admit:" + scheduleId, received, receiptWall, receiptElapsed);
            }
            return null;
        });
        schedules.wake("CALENDAR_VERIFIED", binding.dataEpoch, received.wall().toEpochMilli(), received.elapsedMillis(), success -> { });
    }
    public boolean verifyRun(ScheduleRunEntity run) {
        var rule = ScheduleCodec.rule(schedules.call(store -> store.dao().definition(run.scheduleId)).timeRuleJson);
        if (!(rule instanceof TimeRule.CalendarOffset calendar)) return true;
        var before = schedules.call(store -> new CalendarBindingStore(store).owned(run.ownerUid, calendar.bindingId()));
        reconcile(run.scheduleId);
        var after = schedules.call(store -> new CalendarBindingStore(store).owned(run.ownerUid, calendar.bindingId()));
        return after.state.equals("VALID") && before.sourceRevision.equals(after.sourceRevision);
    }
    private ToolResult invoke(ScheduleIdentity owner, String capability, Map<String,Object> args, String requestId) {
        var executor = host.get().automaticTasks();
        long epoch = schedules.call(ScheduleStore::currentEpoch), began = android.os.SystemClock.elapsedRealtime();
        ExecutionScope scope = ExecutionScope.automatic(Set.of(capability), false, new ExecutionScope.Guard() {
            @Override public String rejection() {
                if (!schedules.userReady() || !schedules.authorized(owner)) return "AUTHORIZATION_REVOKED";
                return schedules.call(store -> store.currentEpoch() == epoch ? "" : "STALE_EPOCH");
            }
            @Override public long remainingMillis() { return 5_000 - (android.os.SystemClock.elapsedRealtime() - began); }
            @Override public boolean reserveTool() { return true; }
        });
        var task = new PreparedAutomaticTask(requestId, requestId, "核验用户选择的日历数据", owner.actor(), owner.zone(), epoch,
                5_000, executor.readOnly(List.of(capability)), scope);
        return executor.tool(task, capability, args, new CancellationToken());
    }
    private static CalendarSnapshot snapshot(Map<String,Object> source) {
        return new CalendarSnapshot(((Number)source.get("startMillis")).longValue(), ((Number)source.get("endMillis")).longValue(),
                source.get("sourceRevision").toString(), source.get("title").toString(), Boolean.TRUE.equals(source.get("allDay")));
    }
    private static JSONObject bindingJson(ScheduleBindingEntity row) {
        JSONObject object = object("bindingId", row.bindingId);
        try { return object.put("eventId", row.eventId).put("calendarId", row.calendarId).put("originalStartMillis", Long.parseLong(row.originalInstanceKey))
                .put("state", row.state).put("reminderOwner", row.reminderOwner).put("snapshot", new JSONObject(row.sourceRevision)); }
        catch (JSONException invalid) { throw new IllegalArgumentException("invalid binding", invalid); }
    }
    private static ScheduleCalendarResult result(ToolResult result) {
        String json = new JSONObject(result.getObservedState()).toString();
        if (json.getBytes(StandardCharsets.UTF_8).length > 128 * 1024) return new ScheduleCalendarResult(OVERLOADED, "TOO_LARGE", "{}", "结果过大，请缩小查询范围");
        return new ScheduleCalendarResult(result.isSuccess() ? SUCCESS : INVALID_STATE, result.getStatus().name(), json, result.getMessage());
    }
    private static JSONObject object(String key, Object value) { try { return new JSONObject().put(key, value); } catch (JSONException impossible) { throw new IllegalArgumentException(impossible); } }
    private static String encodeResult(ScheduleCalendarResult result) {
        try { return object("code", result.code).put("status", result.status).put("payload", result.payloadJson).put("message", result.message).toString(); }
        catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
    private static ScheduleCalendarResult decodeResult(String json) {
        try { var value = new JSONObject(json); return new ScheduleCalendarResult(value.getInt("code"), value.getString("status"), value.getString("payload"), value.getString("message")); }
        catch (JSONException invalid) { throw new IllegalStateException("external receipt unavailable", invalid); }
    }
    private static Map<String, Object> arguments(String encoded) { return ScheduleCodec.arguments(encoded); }
}
