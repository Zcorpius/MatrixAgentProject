package com.matrix.agent.data.db;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.task.AgentIteration;
import com.matrix.agent.task.AgentMessage;
import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.task.StopReason;
import com.matrix.agent.task.TaskState;
import com.matrix.agent.task.ToolObservation;
import com.matrix.agent.task.Trajectory;
import com.matrix.agent.task.policy.PolicyDecision;
import com.matrix.agent.task.tool.ToolResult;

import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TrajectoryCodec round-trip 契约测试。
 *
 * <p>验证 encode → decode 还原 Trajectory 关键字段:
 * iterations 数 / stopReason / durationMillis / totalToolCalls / iteration 内 observation。
 *
 * <p>已知限制:arguments 的 Integer 值 round-trip 后变 Long(org.json 行为),
 * 测试用 String 值避开此限制;引入 schema-aware codec 后修复。
 */
public final class TrajectoryCodecTest {

    @Test
    public void emptyOutcomeRoundTrips() {
        Trajectory trajectory = new Trajectory(1000L);
        trajectory.finish(StopReason.NO_TOOL_CALL, 500L, 0);
        AgentOutcome outcome = new AgentOutcome("req-1", TaskState.SUCCEEDED,
                StopReason.NO_TOOL_CALL, trajectory, 500L);

        String json = TrajectoryCodec.encode(outcome);
        Trajectory decoded = TrajectoryCodec.decodeTrajectory(json);

        assertNotNull(decoded);
        assertEquals(StopReason.NO_TOOL_CALL, decoded.getStopReason());
        assertEquals(500L, decoded.getDurationMillis());
        assertEquals(0, decoded.getTotalToolCalls());
        assertTrue(decoded.getIterations().isEmpty());
    }

    @Test
    public void outcomeWithOneToolCallRoundTrips() {
        Trajectory trajectory = new Trajectory(2000L);

        // iteration 1: assistant message + 1 tool call (SUCCESS)
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("zone", "driver");  // String 值,避免 Integer→Long 类型变化
        args.put("temperature", "24");
        AgentIteration.ToolCallSnapshot snapshot = new AgentIteration.ToolCallSnapshot(
                "step-1", "vehicle.climate.set_temperature", args);

        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("driver_temp", "24");
        ToolResult toolResult = new ToolResult(ToolResult.Status.SUCCESS,
                "vehicle.climate.set_temperature", "OK", observed, true, 120L);
        ToolObservation observation = ToolObservation.fromParts(
                "step-1", "vehicle.climate.set_temperature", toolResult, null, false);

        AgentIteration iteration = new AgentIteration(1,
                AgentMessage.assistant("调温到 24 度", Collections.emptyList()),
                Collections.singletonList(snapshot),
                Collections.singletonList(observation),
                Collections.singletonList(PolicyDecision.allow()),
                300L);
        trajectory.addIteration(iteration);
        trajectory.finish(StopReason.NO_TOOL_CALL, 800L, 1);

        AgentOutcome outcome = new AgentOutcome("req-2", TaskState.SUCCEEDED,
                StopReason.NO_TOOL_CALL, trajectory, 800L);

        String json = TrajectoryCodec.encode(outcome);
        Trajectory decoded = TrajectoryCodec.decodeTrajectory(json);

        assertEquals(StopReason.NO_TOOL_CALL, decoded.getStopReason());
        assertEquals(800L, decoded.getDurationMillis());
        assertEquals(1, decoded.getTotalToolCalls());
        assertEquals(1, decoded.getIterations().size());

        AgentIteration decodedIter = decoded.getIterations().get(0);
        assertEquals(1, decodedIter.getIteration());
        assertEquals(300L, decodedIter.getDurationMillis());
        assertEquals("调温到 24 度", decodedIter.getAssistantMessage().getContent());

        List<AgentIteration.ToolCallSnapshot> decodedCalls = decodedIter.getToolCalls();
        assertEquals(1, decodedCalls.size());
        assertEquals("step-1", decodedCalls.get(0).getToolCallId());
        assertEquals("vehicle.climate.set_temperature", decodedCalls.get(0).getCapabilityName());
        assertEquals("driver", decodedCalls.get(0).getArguments().get("zone"));
        assertEquals("24", decodedCalls.get(0).getArguments().get("temperature"));

        List<ToolObservation> decodedObs = decodedIter.getObservations();
        assertEquals(1, decodedObs.size());
        assertEquals("vehicle.climate.set_temperature", decodedObs.get(0).getCapabilityName());
        assertNotNull(decodedObs.get(0).getResult());
        assertEquals(ToolResult.Status.SUCCESS, decodedObs.get(0).getResult().getStatus());
        assertTrue(decodedObs.get(0).getResult().isVerified());

        List<PolicyDecision> decodedDecisions = decodedIter.getPolicyDecisions();
        assertEquals(1, decodedDecisions.size());
        assertTrue(decodedDecisions.get(0).isAllowed());
    }

    @Test
    public void outcomeWithPolicyRejectionRoundTrips() {
        Trajectory trajectory = new Trajectory(3000L);
        ToolObservation rejected = ToolObservation.fromParts("step-x",
                "vehicle.climate.set_temperature", null,
                "任务被标记为只读(readOnlyHint=true),禁止执行写操作", true);
        AgentIteration iteration = new AgentIteration(1,
                AgentMessage.assistant("", Collections.emptyList()),
                Collections.emptyList(),
                Collections.singletonList(rejected),
                Collections.singletonList(PolicyDecision.denyCapability(
                        "readOnlyHint=true 禁止执行写操作")),
                100L);
        trajectory.addIteration(iteration);
        trajectory.finish(StopReason.POLICY_HALT, 200L, 0);

        AgentOutcome outcome = new AgentOutcome("req-3", TaskState.FAILED,
                StopReason.POLICY_HALT, trajectory, 200L);

        Trajectory decoded = TrajectoryCodec.decodeTrajectory(TrajectoryCodec.encode(outcome));

        assertEquals(StopReason.POLICY_HALT, decoded.getStopReason());
        assertEquals(1, decoded.getIterations().size());

        ToolObservation decodedObs = decoded.getIterations().get(0).getObservations().get(0);
        assertEquals("step-x", decodedObs.getToolCallId());
        assertEquals(true, decodedObs.isCapabilityBlocked());
        assertEquals("任务被标记为只读(readOnlyHint=true),禁止执行写操作",
                decodedObs.getRejectionReason());

        PolicyDecision decodedDecision = decoded.getIterations().get(0).getPolicyDecisions().get(0);
        assertEquals(false, decodedDecision.isAllowed());
        assertEquals(PolicyDecision.RejectionType.CAPABILITY, decodedDecision.getRejectionType());
    }

    @Test
    public void encodeNullOutcomeReturnsEmpty() {
        assertEquals("", TrajectoryCodec.encode(null));
    }

    @Test
    public void decodeEmptyJsonReturnsFreshTrajectory() {
        Trajectory decoded = TrajectoryCodec.decodeTrajectory("");
        assertNotNull(decoded);
        assertTrue(decoded.getIterations().isEmpty());
    }

    /**
     * 抢占路径 PREEMPTED 重映射后,outcome.stopReason 必须与
     * trajectory.stopReason 一致——否则 Audit 落库后结构化列(PREEMPTED)与
     * trajectoryJson.trajectory.stopReason(CANCELLED)矛盾,列表页显示"被抢占"但
     * 回放 JSON 解码为"已取消"。
     *
     * <p>模拟 Engine 在 token.cancel 后用 CANCELLED finish trajectory,Scheduler
     * remapIfPreempted 调 {@link Trajectory#rewriteStopReason} 覆盖为 PREEMPTED。
     * round-trip 后 AuditRecord.stopReason(从 outcome.stopReason 来)与
     * decodeTrajectory(...).getStopReason()(从 trajectory.stopReason 来)必须一致。
     */
    @Test
    public void preemptedOutcomeStopReasonMatchesTrajectoryAfterRewrite() {
        Trajectory trajectory = new Trajectory(4000L);
        trajectory.finish(StopReason.CANCELLED, 250L, 0);

        // Scheduler.remapIfPreempted 触发:重写 trajectory stopReason
        trajectory.rewriteStopReason(StopReason.PREEMPTED);
        AgentOutcome outcome = new AgentOutcome("req-preempted", TaskState.PREEMPTED,
                StopReason.PREEMPTED, trajectory, 250L);

        // 修复后契约:outcome.stopReason == outcome.trajectory.stopReason
        assertEquals("outcome.stopReason 必须与 trajectory.stopReason 一致",
                outcome.getStopReason(), outcome.getTrajectory().getStopReason());

        // round-trip 后 AuditRecord.stopReason 与 decodeTrajectory(...).getStopReason() 一致
        String json = TrajectoryCodec.encode(outcome);
        Trajectory decoded = TrajectoryCodec.decodeTrajectory(json);
        assertEquals("round-trip 后 trajectory.stopReason 必须保持 PREEMPTED",
                StopReason.PREEMPTED, decoded.getStopReason());
        assertEquals("round-trip 后 AuditRecord.stopReason(outcome)与 Trajectory.stopReason 必须一致",
                outcome.getStopReason(), decoded.getStopReason());
    }

    /**
     * 正常路径所有终态(SUCCEEDED / FAILED / CANCELLED / TIMED_OUT / PREEMPTED)
     * 都必须满足 outcome.stopReason == trajectory.stopReason,round-trip 后仍一致。
     */
    @Test
    public void allTerminalStatesStopReasonConsistentThroughCodec() {
        StopReason[] reasons = {
                StopReason.NO_TOOL_CALL,
                StopReason.POLICY_HALT,
                StopReason.CANCELLED,
                StopReason.TIMEOUT,
                StopReason.PREEMPTED,
        };
        for (StopReason reason : reasons) {
            Trajectory trajectory = new Trajectory(5000L);
            trajectory.finish(reason, 100L, 0);
            AgentOutcome outcome = new AgentOutcome("req-" + reason.name(),
                    TaskState.SUCCEEDED, reason, trajectory, 100L);

            assertEquals("outcome.stopReason 必须与 trajectory.stopReason 一致 (" + reason + ")",
                    outcome.getStopReason(), outcome.getTrajectory().getStopReason());

            Trajectory decoded = TrajectoryCodec.decodeTrajectory(TrajectoryCodec.encode(outcome));
            assertEquals("round-trip 后 trajectory.stopReason 必须保持原值 (" + reason + ")",
                    reason, decoded.getStopReason());
            assertEquals("round-trip 后 outcome.stopReason 与 trajectory.stopReason 必须一致 ("
                    + reason + ")",
                    outcome.getStopReason(), decoded.getStopReason());
        }
    }

    /** rewriteStopReason 在 finish 之前调用必须抛 IllegalStateException(防御滥用)。 */
    @Test(expected = IllegalStateException.class)
    public void rewriteStopReasonThrowsBeforeFinish() {
        Trajectory trajectory = new Trajectory();
        trajectory.rewriteStopReason(StopReason.PREEMPTED);
    }

    /** rewriteStopReason null 参数必须抛 IllegalArgumentException。 */
    @Test(expected = IllegalArgumentException.class)
    public void rewriteStopReasonThrowsOnNull() {
        Trajectory trajectory = new Trajectory();
        trajectory.finish(StopReason.NO_TOOL_CALL, 0L, 0);
        trajectory.rewriteStopReason(null);
    }
}
