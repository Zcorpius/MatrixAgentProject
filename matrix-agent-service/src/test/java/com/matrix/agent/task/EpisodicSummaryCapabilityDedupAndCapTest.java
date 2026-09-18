package com.matrix.agent.task;

import com.matrix.agent.task.persistence.EpisodicSummary;

import com.matrix.agent.contract.AgentMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.task.tool.ToolResult;

/**
 * EpisodicSummary.successfulCapabilities 去重 + ≤3 截断测试。
 *
 * <p>用户硬约束:"最多 3 个能力"。任务调 10 个工具也只记前 3 个(去重),
 * 防止大表撑爆。EpisodicMemorySourceImpl.toSnippets 不读 trajectoryJson,所以截断不影响召回。
 */
public final class EpisodicSummaryCapabilityDedupAndCapTest {

    @Test
    public void dedupAndCapAtMostThreeCapabilities() {
        AgentRequest request = AgentRequest.builder("复杂任务", Actor.DRIVER).build();
        Trajectory trajectory = new Trajectory(1L);

        // 5 个成功工具调用,其中 climate.set_temperature 重复 3 次
        trajectory.addIteration(iterationWithSuccessful("climate.set_temperature"));
        trajectory.addIteration(iterationWithSuccessful("navigation.start"));
        trajectory.addIteration(iterationWithSuccessful("climate.set_temperature"));
        trajectory.addIteration(iterationWithSuccessful("media.play"));
        trajectory.addIteration(iterationWithSuccessful("climate.set_temperature"));
        trajectory.addIteration(iterationWithSuccessful("phone.call"));

        AgentOutcome outcome = new AgentOutcome(request.getRequestId(),
                TaskState.SUCCEEDED, StopReason.DONE, trajectory, 1000L);

        EpisodicSummary summary = EpisodicSummary.build(request, outcome);

        List<String> caps = summary.getSuccessfulCapabilities();
        assertEquals("dedupe 后 ≤3", 3, caps.size());
        // 排序后:climate.set_temperature < media.play < navigation.start
        assertEquals("climate.set_temperature", caps.get(0));
        assertEquals("media.play", caps.get(1));
        assertEquals("navigation.start", caps.get(2));
    }

    @Test
    public void onlySuccessfulCapabilitiesCollected() {
        AgentRequest request = AgentRequest.builder("混合任务", Actor.DRIVER).build();
        Trajectory trajectory = new Trajectory(1L);
        trajectory.addIteration(iterationWithSuccessful("navigation.start"));
        trajectory.addIteration(iterationWithFailed("climate.set_temperature"));
        trajectory.addIteration(iterationWithSuccessful("media.play"));

        AgentOutcome outcome = new AgentOutcome(request.getRequestId(),
                TaskState.SUCCEEDED, StopReason.DONE, trajectory, 500L);

        EpisodicSummary summary = EpisodicSummary.build(request, outcome);

        // navigation.start + media.play(climate.set_temperature 失败不收)
        List<String> caps = summary.getSuccessfulCapabilities();
        assertEquals(2, caps.size());
        assertTrue(caps.contains("navigation.start"));
        assertTrue(caps.contains("media.play"));
        assertTrue("failed capability 不进 summary",
                !caps.contains("climate.set_temperature"));
    }

    @Test
    public void noSuccessfulCapabilitiesYieldsEmptyList() {
        AgentRequest request = AgentRequest.builder("全失败", Actor.DRIVER).build();
        Trajectory trajectory = new Trajectory(1L);
        trajectory.addIteration(iterationWithFailed("a.b"));
        trajectory.addIteration(iterationWithFailed("c.d"));

        AgentOutcome outcome = new AgentOutcome(request.getRequestId(),
                TaskState.SUCCEEDED, StopReason.DONE, trajectory, 100L);

        EpisodicSummary summary = EpisodicSummary.build(request, outcome);
        assertEquals(0, summary.getSuccessfulCapabilities().size());
    }

    private static AgentIteration iterationWithSuccessful(String capabilityName) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("dummy", "value");
        ToolCall call = new ToolCall(capabilityName, args);
        ToolResult result = new ToolResult(ToolResult.Status.SUCCESS, capabilityName, "ok",
                Collections.<String, Object>emptyMap(), true, 10L);
        ToolObservation obs = ToolObservation.of(call, result);
        return new AgentIteration(1, AgentMessage.assistant("planning", Collections.emptyList()),
                Arrays.asList(new AgentIteration.ToolCallSnapshot("tc-1", capabilityName, args)),
                Arrays.asList(obs), Collections.emptyList(), 100L);
    }

    private static AgentIteration iterationWithFailed(String capabilityName) {
        Map<String, Object> args = new LinkedHashMap<>();
        ToolCall call = new ToolCall(capabilityName, args);
        ToolResult fail = new ToolResult(ToolResult.Status.EXECUTION_FAILED,
                capabilityName, "failed", Collections.<String, Object>emptyMap(), false, 5L);
        ToolObservation obs = ToolObservation.of(call, fail);
        return new AgentIteration(1, AgentMessage.assistant("planning", Collections.emptyList()),
                Arrays.asList(new AgentIteration.ToolCallSnapshot("tc-1", capabilityName, args)),
                new ArrayList<>(Arrays.asList(obs)), Collections.emptyList(), 100L);
    }
}
