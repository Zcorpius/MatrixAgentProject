package com.matrix.agent.demo;

import static org.junit.Assert.assertEquals;

import com.matrix.agent.contract.AgentMessage;
import com.matrix.agent.contract.ModelTurn;
import com.matrix.agent.contract.ModelTurnRequest;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.session.SessionContext;
import com.matrix.agent.task.capability.CapabilityRegistry;

import org.junit.Test;

import java.util.Collections;

/** Offline planner remains usable for the same explicit system-control commands as cloud models. */
public final class DemoModelGatewaySystemControlTest {
    @Test public void routesExplicitMediaVolumePercentage() {
        ModelTurn turn = decide("把媒体音量调到35%");
        assertEquals("system.media.set_volume", turn.getToolCalls().get(0).getCapabilityName());
        assertEquals(35, ((Number) turn.getToolCalls().get(0).argument("percent")).intValue());
    }

    @Test public void routesExplicitScreenBrightnessPercentage() {
        ModelTurn turn = decide("把屏幕亮度设为45%");
        assertEquals("system.display.set_brightness", turn.getToolCalls().get(0).getCapabilityName());
        assertEquals(45, ((Number) turn.getToolCalls().get(0).argument("percent")).intValue());
    }

    private static ModelTurn decide(String command) {
        AgentRequest request = AgentRequest.builder(command, Actor.DRIVER).build();
        return new DemoModelGateway().decide(new ModelTurnRequest(request,
                Collections.singletonList(AgentMessage.user(command)),
                CapabilityRegistry.createDemoRegistry().toToolDefinitions(), "system",
                new SessionContext()));
    }
}
