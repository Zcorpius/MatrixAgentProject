package com.matrix.agent.task.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.identity.InteractiveOrigin;
import com.matrix.agent.task.capability.CalendarClockCapabilities;
import com.matrix.agent.task.capability.CapabilityRegistry;
import java.util.Map;
import org.junit.Test;

public final class WeatherAlarmIntentPolicyTest {
    private final PolicyEngine policy = new PolicyEngine(
            CalendarClockCapabilities.registerInto(new CapabilityRegistry()));
    private final ToolCall alarm = new ToolCall("clock.set_alarm",
            Map.of("hour", 9, "minute", 10, "label", "天气"));

    @Test public void mixedWeatherAlarmCannotSilentlyDelegateToDeskClock() {
        PolicyDecision result = policy.evaluate(request("制定每天九点十分的闹钟，并告诉我当天的天气情况"), alarm);
        assertFalse(result.isAllowed());
        assertEquals(PolicyDecision.RejectionType.CAPABILITY, result.getRejectionType());
        assertTrue(result.getReason().contains("Agent 天气提醒"));
        assertFalse(policy.evaluate(request("每天 09:10 提醒我看当天的天气"), alarm).isAllowed());
    }

    @Test public void explicitlySeparateNativeAlarmCanStillBeDelegated() {
        assertTrue(policy.evaluate(request("另外单独创建系统闹钟，每天九点十分响铃；天气提醒也单独创建"), alarm).isAllowed());
        assertTrue(policy.evaluate(request("设置每天九点十分的闹钟"), alarm).isAllowed());
    }

    private static AgentRequest request(String user) {
        return AgentRequest.builder(user, Actor.DRIVER)
                .interactiveOrigin(new InteractiveOrigin(10001, 0, "synthetic.owner", user))
                .build();
    }
}
