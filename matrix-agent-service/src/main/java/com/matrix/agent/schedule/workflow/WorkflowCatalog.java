package com.matrix.agent.schedule.workflow;

import com.matrix.agent.api.schedule.*;
import java.util.*;

/** Released versions are immutable. Weather/routing are deliberately absent until real providers exist. */
public final class WorkflowCatalog {
    private WorkflowCatalog() { }
    private static final List<WorkflowTemplate> TEMPLATES = List.of(template(false), template(true), ResearchWorkflow.template());
    private static WorkflowTemplate template(boolean agent) {
        return new WorkflowTemplate(agent ? "daily_agenda_agent" : "daily_agenda", 1,
                agent ? "Agent 日程简报" : "每日行程提醒",
                agent ? "并行读取今天与明天日程，Agent 在授权范围内整理简报，最后通知。明日查询失败可降级。"
                        : "并行读取今天与明天的日历，生成确定性摘要并通知；无需模型或网络。明日查询为可选步骤。",
                List.of(new WorkflowTemplate.Step("today", "读取今日日程", WorkflowTemplate.Kind.TOOL, List.of(), true, true, "calendar.query", 10_000, 2, ""),
                        new WorkflowTemplate.Step("tomorrow", "读取明日日程", WorkflowTemplate.Kind.TOOL, List.of(), false, true, "calendar.query", 10_000, 2, ""),
                        new WorkflowTemplate.Step("summary", agent ? "Agent 整理简报" : "整理日程摘要", agent ? WorkflowTemplate.Kind.AGENT : WorkflowTemplate.Kind.TRANSFORM,
                                List.of("today", "tomorrow"), true, true, "", 40_000, 0, ""),
                        new WorkflowTemplate.Step("deliver", "通知与可选播报", WorkflowTemplate.Kind.DELIVER, List.of("summary"), true, false, "", 15_000, 0, "schedule-notifications")));
    }
    public static WorkflowTemplate require(String id, int version) {
        for (WorkflowTemplate template : TEMPLATES) if (template.id().equals(id) && template.version() == version) return template;
        throw new IllegalArgumentException("不支持的模板版本");
    }
    public static List<ScheduleTemplateInfo> describe() {
        List<ScheduleTemplateInfo> result = new ArrayList<>();
        for (WorkflowTemplate template : TEMPLATES) {
            List<ScheduleStepInfo> steps = new ArrayList<>();
            for (var step : template.steps()) steps.add(new ScheduleStepInfo("", step.id(), step.title(), step.dependencies(), step.required(), ScheduleCodes.WAITING_DEPENDENCY, 0, 0, 0, "", ""));
            result.add(new ScheduleTemplateInfo(template.id(), template.version(), template.title(), template.description(),
                    List.copyOf(template.capabilities()), steps, ResearchWorkflow.isResearch(template) ? "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"maxLength\":300}},\"required\":[\"query\"],\"additionalProperties\":false}" : "{\"type\":\"object\",\"properties\":{\"includeTomorrow\":{\"type\":\"boolean\"},\"calendarId\":{\"type\":\"integer\"}},\"additionalProperties\":false}"));
        }
        return List.copyOf(result);
    }
    public static void validate(ScheduleAction action) {
        WorkflowTemplate template = require(action.templateId, action.templateVersion);
        if (!Set.copyOf(action.capabilities).equals(template.capabilities())) throw new IllegalArgumentException("模板能力授权不完整或超出范围");
        try {
            org.json.JSONObject parameters = new org.json.JSONObject(action.parametersJson);
            if (ResearchWorkflow.isResearch(template)) { ResearchWorkflow.validate(action, parameters); return; }
            for (var keys = parameters.keys(); keys.hasNext();) {
                String key = keys.next(); Object value = parameters.get(key);
                if (key.equals("includeTomorrow") && value instanceof Boolean) continue;
                if (key.equals("calendarId") && value instanceof Number number && number.longValue() > 0 && number.doubleValue() == number.longValue()) continue;
                throw new IllegalArgumentException("模板参数不合法：" + key);
            }
        } catch (org.json.JSONException invalid) { throw new IllegalArgumentException("模板参数不合法"); }
    }
}
