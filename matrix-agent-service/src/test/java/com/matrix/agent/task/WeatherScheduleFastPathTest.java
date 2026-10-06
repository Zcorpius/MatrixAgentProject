package com.matrix.agent.task;

import static com.matrix.agent.api.schedule.ScheduleCodes.DRAFT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.identity.InteractiveOrigin;
import com.matrix.agent.task.tool.ToolResult;
import java.util.Map;
import org.junit.Test;

public final class WeatherScheduleFastPathTest {
    @Test public void screenshotRequestBecomesAnUnprivilegedWeatherDraftCall() {
        AgentRequest request = request("那制定每天九点十分的闹钟，并告诉我当天的天气情况");
        assertTrue(WeatherScheduleToolProjection.eligible(request));
        ToolCall call = WeatherScheduleFastPath.plan(request);
        assertNotNull(call);
        assertEquals("schedule.create", call.getCapabilityName());
        assertEquals("09:10", call.argument("localTime"));
        assertEquals("daily_weather_current", call.argument("templateId"));
        assertEquals(false, call.argument("allowLocation"));
        assertEquals(false, call.argument("allowNetwork"));
        assertEquals(false, call.argument("speakResult"));
        String answer = WeatherScheduleFastPath.answer(call, new ToolResult(ToolResult.Status.SUCCESS,
                "schedule.create", "saved", Map.of("state", DRAFT), true, 1));
        assertTrue(answer.contains("草稿"));
        assertTrue(answer.contains("尚未启用"));
    }

    @Test public void numericTimeAndSafetyExclusions() {
        assertEquals("10:10", WeatherScheduleFastPath.plan(
                request("那制定每天10:10的闹钟，并告诉我当天的天气情况")).argument("localTime"));
        assertNull(WeatherScheduleFastPath.plan(request("每天九点左右提醒我天气并告诉我")));
        assertNull(WeatherScheduleFastPath.plan(request("制定每天九点和十点的闹钟，并告诉我天气")));
        assertNull(WeatherScheduleFastPath.plan(request("如何制定每天九点的天气闹钟？")));
        assertNull(WeatherScheduleFastPath.plan(request("单独创建系统闹钟并每天告诉我天气")));
        assertFalse(WeatherScheduleFastPath.answer(WeatherScheduleFastPath.plan(
                request("制定每天九点的闹钟并告诉我天气")), ToolResult.rejected("schedule.create", "时间不明确"))
                .contains("已保存"));
    }

    private static AgentRequest request(String user) {
        return AgentRequest.builder(user, Actor.DRIVER)
                .interactiveOrigin(new InteractiveOrigin(10001, 0, "synthetic.owner", user)).build();
    }
}
