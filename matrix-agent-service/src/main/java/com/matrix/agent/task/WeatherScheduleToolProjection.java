package com.matrix.agent.task;

import com.matrix.agent.contract.ToolDefinition;
import com.matrix.agent.identity.AgentRequest;
import java.util.List;

/** Narrows an explicit weather-plan creation turn to the single operation the user requested. */
final class WeatherScheduleToolProjection {
    private WeatherScheduleToolProjection() { }

    static List<ToolDefinition> project(AgentRequest request, List<ToolDefinition> available) {
        if (!eligible(request)) return available;
        List<ToolDefinition> create = available.stream()
                .filter(tool -> "schedule.create".equals(tool.getCapabilityName()))
                .collect(java.util.stream.Collectors.toList());
        return create.isEmpty() ? available : create;
    }

    static boolean eligible(AgentRequest request) {
        if (request.getInteractiveOrigin() == null) return false;
        String user = request.getInteractiveOrigin().userText();
        return user.contains("天气") && (user.contains("每天") || user.contains("每日")
                || user.contains("每周") || user.contains("闹钟") || user.contains("提醒"))
                && user.matches("(?s).*(制定|指定|设置|设定|创建|安排|提醒我).*")
                && !user.matches("(?s).*(怎么|如何|为什么|能否|可以吗|不要|别创建|仅预览|只预览).*")
                && !user.matches("(?s).*(日历|日程|音乐|空调|导航|座椅).*")
                && !user.matches("(?is).*(系统闹钟|原生闹钟|deskclock|时钟应用).*");
    }
}
