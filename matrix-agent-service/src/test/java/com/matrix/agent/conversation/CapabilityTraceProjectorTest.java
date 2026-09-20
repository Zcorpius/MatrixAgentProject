package com.matrix.agent.conversation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.task.AgentIteration;
import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.task.StopReason;
import com.matrix.agent.task.TaskState;
import com.matrix.agent.task.ToolObservation;
import com.matrix.agent.task.tool.ToolResult;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 能力事实轨迹投影（评估 v1.0 §4.3）：两段式核验、白名单净化、未知态语义。 */
public final class CapabilityTraceProjectorTest {

    private static AgentIteration iteration(ToolCall call, ToolObservation observation) {
        AgentIteration.ToolCallSnapshot snapshot = new AgentIteration.ToolCallSnapshot(
                call.getStepId(), call.getCapabilityName(), call.getArguments());
        return new AgentIteration(1,
                com.matrix.agent.contract.AgentMessage.assistant("规划", List.of(call)),
                List.of(snapshot), List.of(observation), List.of(), 10L);
    }

    private static ToolResult success(String capability, String message,
            Map<String, Object> observed, long durationMillis) {
        return new ToolResult(ToolResult.Status.SUCCESS, capability, message, observed,
                true, durationMillis);
    }

    private static AgentOutcome outcomeOf(AgentIteration... iterations) {
        com.matrix.agent.task.Trajectory trajectory =
                new com.matrix.agent.task.Trajectory();
        for (AgentIteration iteration : iterations) {
            trajectory.addIteration(iteration);
        }
        trajectory.finish(StopReason.NO_TOOL_CALL, 1L, iterations.length);
        return new AgentOutcome(UUID.randomUUID().toString(), TaskState.SUCCEEDED,
                StopReason.NO_TOOL_CALL, trajectory, 1L, List.of(), "完成");
    }

    /** 音量量化场景：请求 30%，读回 33% → 两段式 + MISMATCH 语义（以读回为准）。 */
    @Test public void volumeQuantizationProducesTwoPartMismatch() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("percent", 30);
        ToolCall call = new ToolCall("system.media.volume", args);
        Map<String, Object> readback = new LinkedHashMap<>();
        readback.put("media.volume.percent", 33);
        ToolObservation observation = ToolObservation.of(call,
                new ToolResult(ToolResult.Status.VERIFICATION_FAILED,
                        "system.media.volume", "媒体音量已设置为 33%", readback,
                        false, 40L));

        List<CapabilityExecutionTrace> traces =
                CapabilityTraceProjector.project(outcomeOf(iteration(call, observation)));

        assertEquals(1, traces.size());
        CapabilityExecutionTrace trace = traces.get(0);
        assertEquals("system.media.volume", trace.capabilityId);
        assertEquals("percent=30", trace.requestedDisplay);
        assertEquals("percent=33", trace.verifiedDisplay);
        assertEquals(CapabilityExecutionTrace.OUTCOME_FAILED, trace.outcome);
        assertEquals(CapabilityExecutionTrace.VERIFY_MISMATCH, trace.verificationState);
    }

    /** 白名单外参数一律省略；长文本/对象值不展示。 */
    @Test public void nonWhitelistedArgumentsAreOmitted() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("percent", 45);
        args.put("freeText", "把声音调大一点谢谢");
        args.put("callback", Map.of("evil", 1));
        ToolCall call = new ToolCall("system.display.brightness", args);
        ToolObservation observation = ToolObservation.of(call,
                success("system.display.brightness", "屏幕亮度已设置为 45%",
                        Map.of("display.brightness.percent", 45), 30L));

        CapabilityExecutionTrace trace = CapabilityTraceProjector.project(
                outcomeOf(iteration(call, observation))).get(0);

        assertEquals("percent=45", trace.requestedDisplay);
        assertEquals("percent=45", trace.verifiedDisplay);
        assertEquals(CapabilityExecutionTrace.VERIFY_VERIFIED, trace.verificationState);
        assertEquals(CapabilityExecutionTrace.OUTCOME_SUCCESS, trace.outcome);
    }

    /** EXECUTION_UNKNOWN：写操作结果未知 → UNKNOWN/UNKNOWN，保留请求值。 */
    @Test public void executionUnknownKeepsRequestWithUnknownVerification() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("percent", 50);
        ToolCall call = new ToolCall("system.media.volume", args);
        ToolObservation observation = ToolObservation.of(call,
                new ToolResult(ToolResult.Status.EXECUTION_UNKNOWN,
                        "system.media.volume", "命令已下发，结果未知", Map.of(),
                        false, 90L));

        CapabilityExecutionTrace trace = CapabilityTraceProjector.project(
                outcomeOf(iteration(call, observation))).get(0);

        assertEquals("percent=50", trace.requestedDisplay);
        assertNull("无 readback 不编造核验值", trace.verifiedDisplay);
        assertEquals(CapabilityExecutionTrace.OUTCOME_UNKNOWN, trace.outcome);
        assertEquals(CapabilityExecutionTrace.VERIFY_UNKNOWN, trace.verificationState);
    }

    /** Policy 拒绝：REJECTED + 无核验；快照缺失也不崩（防御）。 */
    @Test public void policyRejectionProjectsRejectedWithoutVerification() {
        ToolCall call = new ToolCall("system.media.volume", Map.of("percent", 999));
        ToolObservation rejected = ToolObservation.rejected(call, "超出范围", false);

        CapabilityExecutionTrace trace = CapabilityTraceProjector.project(
                outcomeOf(iteration(call, rejected))).get(0);

        assertEquals(CapabilityExecutionTrace.OUTCOME_REJECTED, trace.outcome);
        assertEquals(CapabilityExecutionTrace.VERIFY_UNAVAILABLE,
                trace.verificationState);
        // 快照在（同轮 toolCalls 列表）→ 请求摘要保留
        assertEquals("percent=999", trace.requestedDisplay);
    }

    /** 编解码 round-trip：空/畸形 fail-closed。 */
    @Test public void codecRoundTripsAndFailsClosed() {
        CapabilityExecutionTrace trace = new CapabilityExecutionTrace(
                "system.media.volume", "调整媒体音量",
                CapabilityExecutionTrace.OUTCOME_SUCCESS, "percent=30", "percent=33",
                CapabilityExecutionTrace.VERIFY_MISMATCH);
        String json = CapabilityTraceCodec.encode(List.of(trace));
        List<CapabilityExecutionTrace> decoded = CapabilityTraceCodec.decode(json);
        assertEquals(trace.capabilityId, decoded.get(0).capabilityId);
        assertEquals(trace.verifiedDisplay, decoded.get(0).verifiedDisplay);
        assertEquals(trace.verificationState, decoded.get(0).verificationState);

        assertTrue(CapabilityTraceCodec.decode(null).isEmpty());
        org.junit.Assert.assertThrows(IllegalArgumentException.class,
                () -> CapabilityTraceCodec.decode("not-json"));
    }
}
