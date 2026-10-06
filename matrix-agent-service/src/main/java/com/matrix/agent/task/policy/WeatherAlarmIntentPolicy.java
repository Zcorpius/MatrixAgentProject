package com.matrix.agent.task.policy;

import com.matrix.agent.identity.AgentRequest;
import java.util.Locale;

/** Keeps an Agent weather reminder distinct from a delegated, unverified DeskClock alarm. */
final class WeatherAlarmIntentPolicy {
    private WeatherAlarmIntentPolicy() { }

    static PolicyDecision evaluate(AgentRequest request, String capability) {
        if (!"clock.set_alarm".equals(capability) || request.getInteractiveOrigin() == null) return null;
        String user = request.getInteractiveOrigin().userText().toLowerCase(Locale.ROOT);
        if (!user.contains("天气") || !(user.contains("闹钟") || user.contains("提醒")
                || user.contains("每天") || user.contains("每周") || user.contains("定时"))) return null;
        if (user.contains("原生闹钟") || user.contains("系统闹钟")
                || user.contains("deskclock") || user.contains("时钟应用")) return null;
        return PolicyDecision.denyCapability(
                "天气简报应创建 Agent 天气提醒；系统闹钟无法触发天气工作流。若还需要系统时钟响铃，请明确要求单独创建原生闹钟");
    }
}
