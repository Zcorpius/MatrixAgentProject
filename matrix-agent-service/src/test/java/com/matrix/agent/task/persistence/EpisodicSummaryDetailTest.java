package com.matrix.agent.task.persistence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.contract.AgentMessage;
import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.data.memory.EpisodicFactCodec;
import com.matrix.agent.task.AgentIteration;
import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.task.StopReason;
import com.matrix.agent.task.TaskState;
import com.matrix.agent.task.ToolObservation;
import com.matrix.agent.task.Trajectory;
import com.matrix.agent.task.tool.ToolResult;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;
import java.util.Map;

public final class EpisodicSummaryDetailTest {
    @Test public void storesOnlyVerifiedGroundedDestination() throws Exception {
        AgentRequest request = AgentRequest.builder("导航到人民广场", Actor.DRIVER).build();
        ToolResult result = new ToolResult(ToolResult.Status.SUCCESS,
                "navigation.start_route", "到人民广场", Map.of("navigation.destination", "人民广场"),
                true, 10L);
        EpisodicSummary summary = EpisodicSummary.build(request, outcome(request, result));
        JSONObject json = new JSONObject(summary.toJson());
        assertEquals(2, json.getInt("eventSchemaVersion"));
        assertEquals(EpisodicFactCodec.eventIdFor(request,
                summary.getStartedAtMillis()), json.getString("eventId"));
        assertEquals("人民广场", json.getJSONArray("verifiedFacts")
                .getJSONObject(0).getString("value"));
        assertFalse(summary.toJson().contains("导航到人民广场"));
    }

    @Test public void rejectsUngroundedOrUnverifiedDestination() throws Exception {
        AgentRequest request = AgentRequest.builder("导航到人民广场", Actor.DRIVER).build();
        ToolResult invented = new ToolResult(ToolResult.Status.SUCCESS,
                "navigation.start_route", "", Map.of("navigation.destination", "秘密地址"),
                true, 1L);
        assertEquals(0, new JSONObject(EpisodicSummary.build(request, outcome(request, invented))
                .toJson()).getJSONArray("verifiedFacts").length());
        ToolResult unverified = new ToolResult(ToolResult.Status.SUCCESS,
                "navigation.start_route", "", Map.of("navigation.destination", "人民广场"),
                false, 1L);
        assertTrue(new JSONObject(EpisodicSummary.build(request, outcome(request, unverified))
                .toJson()).getJSONArray("verifiedFacts").length() == 0);
    }

    @Test public void retryOfSameRequestGetsDistinctExecutionId() {
        AgentRequest request = AgentRequest.builder("导航到人民广场", Actor.DRIVER)
                .sessionId("same-session").build();
        String first = EpisodicFactCodec.eventIdFor(request, 1000L);
        assertEquals(first, EpisodicFactCodec.eventIdFor(request, 1000L));
        assertFalse(first.equals(EpisodicFactCodec.eventIdFor(request, 1001L)));
    }

    @Test public void verifiedSystemControlReadbackUsesBoundedNumericFacts() throws Exception {
        AgentRequest request = AgentRequest.builder("把媒体音量调到 40，屏幕亮度调到 60", Actor.DRIVER)
                .build();
        ToolResult volume = new ToolResult(ToolResult.Status.SUCCESS,
                "system.media.set_volume", "", Map.of("media.volume.percent", 40), true, 1L);
        ToolResult brightness = new ToolResult(ToolResult.Status.SUCCESS,
                "system.display.set_brightness", "", Map.of("display.brightness.percent", 60),
                true, 1L);
        JSONObject summary = new JSONObject(EpisodicSummary.build(request,
                outcome(request, volume, brightness)).toJson());
        assertEquals(2, summary.getJSONArray("verifiedFacts").length());
        assertEquals("media_volume", summary.getJSONArray("verifiedFacts")
                .getJSONObject(0).getString("kind"));
        assertEquals(60, summary.getJSONArray("verifiedFacts")
                .getJSONObject(1).getInt("value"));
    }

    private static AgentOutcome outcome(AgentRequest request, ToolResult... results) {
        Trajectory trajectory = new Trajectory(System.currentTimeMillis());
        java.util.ArrayList<ToolObservation> observations = new java.util.ArrayList<>();
        for (ToolResult result : results) {
            ToolCall call = new ToolCall(result.getCapabilityName(), Map.of());
            observations.add(ToolObservation.of(call, result));
        }
        trajectory.addIteration(new AgentIteration(1, AgentMessage.assistant("完成", List.of()),
                List.of(), observations, List.of(), 1L));
        trajectory.finish(StopReason.DONE, 10L, results.length);
        return new AgentOutcome(request.getRequestId(), TaskState.SUCCEEDED, StopReason.DONE,
                trajectory, 10L, List.of(results));
    }
}
