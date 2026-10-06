package com.matrix.agent.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.contract.ToolDefinition;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.identity.InteractiveOrigin;
import com.matrix.agent.identity.VehicleZone;
import com.matrix.agent.task.capability.CapabilityRegistry;
import java.util.List;
import org.junit.Test;

public final class WeatherScheduleToolProjectionTest {
    private final List<ToolDefinition> available = CapabilityRegistry.createRuntimeRegistry()
            .toToolDefinitions(VehicleZone.DRIVER);

    @Test public void explicitWeatherCreationOffersOnlyCreate() {
        for (String user : List.of("那制定每天九点十分的闹钟，并告诉我当天的天气情况",
                "那指定每天十点十分的闹钟，并告诉我当天的天气情况")) {
            List<ToolDefinition> projected = WeatherScheduleToolProjection.project(request(user), available);
            assertEquals(1, projected.size());
            assertEquals("schedule.create", projected.get(0).getCapabilityName());
        }
    }

    @Test public void questionsAndMixedDomainsKeepNormalCapabilities() {
        for (String user : List.of("如何制定每天报天气的提醒？", "仅预览每天报天气的提醒",
                "制定每天报天气的提醒，并查询日历")) {
            assertEquals(available.size(), WeatherScheduleToolProjection.project(request(user), available).size());
        }
        assertEquals(available.size(), WeatherScheduleToolProjection.project(
                AgentRequest.builder("制定每天报天气的提醒", Actor.DRIVER).build(), available).size());
        assertTrue(available.size() > 1);
    }

    private static AgentRequest request(String user) {
        return AgentRequest.builder(user, Actor.DRIVER)
                .interactiveOrigin(new InteractiveOrigin(10001, 0, "synthetic.owner", user)).build();
    }
}
