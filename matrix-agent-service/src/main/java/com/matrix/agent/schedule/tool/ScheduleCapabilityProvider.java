package com.matrix.agent.schedule.tool;

import static com.matrix.agent.api.schedule.ScheduleCodes.*;
import com.matrix.agent.api.schedule.*;
import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.schedule.domain.*;
import com.matrix.agent.schedule.store.ScheduleStore;
import com.matrix.agent.schedule.workflow.WorkflowCatalog;
import com.matrix.agent.task.capability.CapabilityProvider;
import com.matrix.agent.task.tool.ToolResult;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

/** Natural-language planning uses the same normalizer and command store as the structured UI. */
public final class ScheduleCapabilityProvider implements CapabilityProvider {
    public interface Backend {
        ScheduleIdentity identity(AgentRequest request);
        ClockSample clock();
        <T> T execute(Function<ScheduleStore, T> action);
        void changed();
    }
    private final Backend backend;
    public ScheduleCapabilityProvider(Backend backend) { this.backend = backend; }
    @Override public ToolResult execute(AgentRequest request, ToolCall call) {
        long began = System.nanoTime(); String capability = call.getCapabilityName();
        try {
            if (request.getExecutionScope().automatic() || request.getInteractiveOrigin() == null) return ToolResult.rejected(capability, "此入口没有可验证的交互式计划管理授权，请使用任务中心");
            ScheduleIdentity identity = backend.identity(request);
            String user = request.getInteractiveOrigin().userText();
            if (capability.equals("schedule.create") && !user.matches("(?s).*(提醒我|提醒一下|设置|创建|安排|定时|每天|每周|分钟后|小时后).*")) return ToolResult.rejected(capability, "当前用户没有明确要求创建计划，请先确认");
            String hash = ScheduleCodec.canonicalObject(new org.json.JSONObject(call.getArguments()).toString());
            String operationId = UUID.nameUUIDFromBytes((request.getRequestId() + ":" + capability + ":" + hash).getBytes(StandardCharsets.UTF_8)).toString();
            Map<String, Object> output;
            String message;
            switch (capability) {
                case "schedule.preview" -> {
                    var clock = backend.clock();
                    var candidate = spec(call, user, clock);
                    ExplicitScheduleIntent.verify(user, candidate, clock, false);
                    var normalized = new ScheduleNormalizer().normalize(candidate, clock);
                    backend.execute(store -> { store.validateAction(normalized.spec().action); return null; });
                    var next = OccurrenceCalculator.next(normalized.rule(), clock.wall(), 1, "preview", clock).orElseThrow();
                    output = Map.of("nextDueAt", next.scheduledAt().toEpochMilli(), "timeZone", normalized.spec().timing.zoneId, "previewOnly", true);
                    message = "计划预览；尚未启用";
                }
                case "schedule.create" -> {
                    var clock = backend.clock();
                    var spec = spec(call, user, clock);
                    ExplicitScheduleIntent.verify(user, spec, clock, true);
                    var result = backend.execute(store -> store.create(identity, spec, operationId, clock));
                    backend.changed(); var plan = backend.execute(store -> store.owned(identity.uid(), result.scheduleId));
                    output = Map.of("scheduleId", result.scheduleId, "revision", result.revision, "nextDueAt", plan.nextDueAt == null ? 0 : plan.nextDueAt,
                            "state", plan.state, "health", plan.health, "code", result.code);
                    message = "计划已保存；系统注册状态请在任务中心核对";
                }
                case "schedule.list" -> {
                    List<Map<String, Object>> plans = backend.execute(store -> {
                        List<Map<String, Object>> rows = new ArrayList<>();
                        for (var plan : store.dao().definitions(identity.uid(), "", 100)) rows.add(Map.of("scheduleId", plan.scheduleId, "revision", plan.revision,
                                "title", ScheduleCodec.spec(plan.specJson).title, "state", plan.state, "health", plan.health, "nextDueAt", plan.nextDueAt == null ? 0 : plan.nextDueAt));
                        return rows;
                    });
                    output = Map.of("plans", plans, "count", plans.size()); message = "计划查询完成";
                }
                case "schedule.control" -> {
                    if (!user.matches("(?s).*(暂停|恢复|删除|取消|跳过|停止).*")) return ToolResult.rejected(capability, "当前用户未明确授权控制计划");
                    int operation = switch (string(call, "operation", "")) { case "PAUSE" -> PAUSE; case "RESUME" -> RESUME; case "DELETE" -> DELETE; case "SKIP_NEXT" -> SKIP_NEXT; case "CANCEL_RUN" -> CANCEL_RUN; case "PAUSE_AND_CANCEL" -> PAUSE_AND_CANCEL; default -> throw new IllegalArgumentException("未知计划控制"); };
                    var result = backend.execute(store -> store.control(identity.uid(), string(call, "scheduleId", ""), string(call, "runId", ""), number(call, "revision", 0), operation, operationId, backend.clock()));
                    backend.changed(); output = Map.of("scheduleId", result.scheduleId, "revision", result.revision, "code", result.code); message = result.message;
                }
                default -> throw new IllegalArgumentException("未知计划能力");
            }
            return new ToolResult(ToolResult.Status.SUCCESS, capability, message, output, true, (System.nanoTime() - began) / 1_000_000);
        } catch (SecurityException | IllegalArgumentException | ScheduleFailure rejected) { return ToolResult.rejected(capability, rejected.getMessage()); }
    }
    private static ScheduleSpec spec(ToolCall call, String user, ClockSample clock) {
        if (user.contains("工作日") && !user.matches("(?s).*(周一至周五|周一到周五|星期一至星期五|星期一到星期五).*")) {
            throw new IllegalArgumentException("工作日可能包含节假日调休，请明确是否按周一至周五执行；当前不支持调休日历");
        }
        int kind = switch (string(call, "timeKind", "")) { case "ONCE" -> ONCE; case "AFTER_DELAY" -> AFTER_DELAY; case "DAILY" -> DAILY; case "WEEKLY" -> WEEKLY; default -> throw new IllegalArgumentException("请明确时间规则"); };
        int action = switch (string(call, "action", "NOTIFICATION")) { case "NOTIFICATION" -> NOTIFICATION; case "AGENT" -> AGENT; case "WORKFLOW" -> WORKFLOW; default -> throw new IllegalArgumentException("未知动作"); };
        boolean calendar = Boolean.TRUE.equals(call.argument("readCalendar"));
        boolean network = Boolean.TRUE.equals(call.argument("allowNetwork")), speak = Boolean.TRUE.equals(call.argument("speakResult"));
        if (calendar && !user.contains("日历") && !user.contains("日程") && !user.contains("行程")) throw new IllegalArgumentException("尚未获得读取日历的明确授权");
        if (network && !user.matches("(?s).*(联网|在线|网络|云端).*")) throw new IllegalArgumentException("在线执行需要用户明确授权，请确认后重试或使用任务中心");
        if (speak && !user.matches("(?s).*(播报|读出来|念出来).*")) throw new IllegalArgumentException("播报需要用户明确选择");
        if (action != NOTIFICATION && !user.matches("(?s).*(执行|生成|整理|摘要|简报|查询|研究).*")) throw new IllegalArgumentException("当前只获得提醒授权，不能扩大成自动执行");
        String template = string(call, "templateId", ""); int version = (int) number(call, "templateVersion", 1);
        List<String> caps = action == WORKFLOW ? List.copyOf(WorkflowCatalog.require(template, version).capabilities()) : calendar ? List.of("calendar.query") : List.of();
        boolean research = action == WORKFLOW && com.matrix.agent.schedule.workflow.ResearchWorkflow.ID.equals(template);
        if (action == WORKFLOW && !research && !calendar) throw new IllegalArgumentException("日程模板需要明确的日历授权");
        String parameters = research ? new org.json.JSONObject(Map.of("query", string(call, "researchQuery", ""))).toString() : "{}";
        return new ScheduleSpec(string(call, "title", ""), new ScheduleTiming(kind, string(call, "timeZone", clock.deviceZone().getId()),
                number(call, "atMillis", 0), Math.multiplyExact(number(call, "afterMinutes", 0), 60_000), string(call, "localTime", ""),
                (int) number(call, "weekdaysMask", 0), "", "", Boolean.TRUE.equals(call.argument("followDeviceZone")), "", 0),
                new ScheduleAction(action, string(call, "text", ""), template, version, parameters, caps, network, speak), research ? com.matrix.agent.schedule.workflow.ResearchWorkflow.DEFAULT_WINDOW_MILLIS : ScheduleNormalizer.DEFAULT_GRACE_MILLIS, WITHIN_GRACE);
    }
    private static String string(ToolCall call, String key, String fallback) { Object value = call.argument(key); return value instanceof String text ? text : fallback; }
    private static long number(ToolCall call, String key, long fallback) { Object value = call.argument(key); if (value == null) return fallback; if (!(value instanceof Number n) || n.doubleValue() != n.longValue() || n.longValue() < 0) throw new IllegalArgumentException("无效参数：" + key); return n.longValue(); }
}
