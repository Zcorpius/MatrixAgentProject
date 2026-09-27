package com.matrix.agent.schedule.store;

import com.matrix.agent.api.schedule.*;
import com.matrix.agent.data.schedule.*;
import com.matrix.agent.schedule.domain.ScheduleCodec;

public final class ScheduleProjection {
    private ScheduleProjection() { }
    public static ScheduleInfo plan(ScheduleDefinitionEntity row, long sequence) {
        return new ScheduleInfo(row.scheduleId, row.revision, row.state, row.health, row.reason,
                value(row.nextDueAt), row.actor, row.zone, ScheduleCodec.spec(row.specJson), sequence);
    }
    public static ScheduleRunInfo run(ScheduleRunEntity row, long sequence) {
        var action = ScheduleCodec.spec(row.specJson).action;
        return new ScheduleRunInfo(com.matrix.agent.api.common.ParcelSchema.CURRENT, row.runId, row.scheduleId, row.title, row.occurrenceKey, row.state,
                row.deliveryStatus, row.scheduledAt, value(row.receivedAt), value(row.admittedAt),
                value(row.startedAt), value(row.completedAt), value(row.deliveredAt), row.result,
                row.reason, row.runtimeRequestId, sequence, row.deliveryFactsJson, action.templateId, action.templateVersion);
    }
    public static ScheduleStepInfo step(ScheduleStepEntity row) {
        try {
            return new ScheduleStepInfo(com.matrix.agent.api.common.ParcelSchema.CURRENT, row.runId, row.stepId, row.title,
                    ScheduleCodec.strings(new org.json.JSONArray(row.dependenciesJson)), row.required,
                    row.state, row.attempt, value(row.startedAt), value(row.completedAt), row.result, row.reason, inputSummary(row), row.activeMillis);
        } catch (org.json.JSONException invalid) { throw new IllegalStateException("invalid stored dependencies", invalid); }
    }
    private static String inputSummary(ScheduleStepEntity row) throws org.json.JSONException {
        if (row.kind.equals("TOOL")) {
            var input = new org.json.JSONObject(row.inputJson);
            if (input.has("startMillis") && input.has("endMillis")) return "查询范围："
                    + java.time.Instant.ofEpochMilli(input.getLong("startMillis")) + " 至 "
                    + java.time.Instant.ofEpochMilli(input.getLong("endMillis"))
                    + (input.has("calendarId") ? "；日历编号 " + input.getLong("calendarId") : "；已授权可读日历");
        }
        return switch (row.kind) {
            case "AGENT" -> "根据前置步骤读取的日历数据生成摘要";
            case "TRANSFORM" -> "汇总前置步骤的日程读取结果";
            case "DELIVER" -> "交付本次运行已生成的摘要";
            default -> "已冻结的步骤输入";
        };
    }
    private static long value(Long value) { return value == null ? 0 : value; }
}
