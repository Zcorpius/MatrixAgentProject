package com.matrix.agent.task;

import com.matrix.agent.task.persistence.EpisodicSummary;

import com.matrix.agent.contract.AgentMessage;

import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.task.tool.ToolResult;

/**
 * EpisodicSummary.summaryJson 长度上限测试。
 *
 * <p>用户硬约束:"限制在 1-2KB"。即便 trajectory 有 100 个 iteration,
 * summaryJson.length() 必须 ≤ {@link EpisodicSummary#MAX_SUMMARY_JSON_LENGTH}(2048)。
 */
public final class EpisodicSummarySizeCapTest {

    @Test
    public void emptyTrajectorySummaryUnderCap() {
        AgentRequest request = AgentRequest.builder("q", Actor.DRIVER).build();
        AgentOutcome outcome = new AgentOutcome(request.getRequestId(),
                TaskState.SUCCEEDED, StopReason.DONE, new Trajectory(1L), 0L);

        EpisodicSummary summary = EpisodicSummary.build(request, outcome);

        assertTrue("空 trajectory summary 长度 ≤ 2048, got "
                + summary.toJson().length(),
                summary.toJson().length() <= EpisodicSummary.MAX_SUMMARY_JSON_LENGTH);
    }

    @Test
    public void hundredIterationsStillUnderCap() {
        AgentRequest request = AgentRequest.builder("复杂任务", Actor.DRIVER).build();
        Trajectory trajectory = new Trajectory(1L);
        // 100 iteration,每个有 5 个成功 capability(不同名,逼出最大 successfulCapabilities)
        for (int i = 0; i < 100; i++) {
            trajectory.addIteration(iterationWithSuccessful(
                    "cap_" + i + "_0", "cap_" + i + "_1", "cap_" + i + "_2",
                    "cap_" + i + "_3", "cap_" + i + "_4"));
        }
        AgentOutcome outcome = new AgentOutcome(request.getRequestId(),
                TaskState.SUCCEEDED, StopReason.DONE, trajectory, 99999L);

        EpisodicSummary summary = EpisodicSummary.build(request, outcome);

        assertTrue("100 iteration 后 successfulCapabilities ≤3, summary ≤2048, got "
                + summary.toJson().length() + " caps=" + summary.getSuccessfulCapabilities().size(),
                summary.toJson().length() <= EpisodicSummary.MAX_SUMMARY_JSON_LENGTH);
        assertTrue("capability 截断到 ≤3",
                summary.getSuccessfulCapabilities().size() <= EpisodicSummary.MAX_SUCCESSFUL_CAPABILITIES);
    }

    private static AgentIteration iterationWithSuccessful(String... capabilityNames) {
        Map<String, Object> args = new LinkedHashMap<>();
        java.util.List<AgentIteration.ToolCallSnapshot> snapshots = new java.util.ArrayList<>();
        java.util.List<ToolObservation> observations = new java.util.ArrayList<>();
        for (String cap : capabilityNames) {
            ToolCall call = new ToolCall(cap, args);
            ToolResult result = new ToolResult(ToolResult.Status.SUCCESS, cap, "ok",
                    Collections.<String, Object>emptyMap(), true, 1L);
            observations.add(ToolObservation.of(call, result));
            snapshots.add(new AgentIteration.ToolCallSnapshot("tc-" + cap, cap, args));
        }
        return new AgentIteration(1, AgentMessage.assistant("p", Collections.emptyList()),
                snapshots, observations, Collections.emptyList(), 1L);
    }
}
