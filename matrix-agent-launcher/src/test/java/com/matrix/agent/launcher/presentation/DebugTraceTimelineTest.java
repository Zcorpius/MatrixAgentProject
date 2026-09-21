package com.matrix.agent.launcher.presentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.api.debug.DebugTracePayloads;
import com.matrix.agent.api.debug.DebugTraceWireEvent;
import com.matrix.agent.launcher.R;

import org.junit.Test;

import java.util.List;

/**
 * Locks down the Operit-style grouping contract without requiring an Android View hierarchy.
 * Payloads are built with the same {@link DebugTracePayloads} helpers the Host emitter uses,
 * so a wire-format drift breaks these tests instead of the debug UI silently.
 */
public final class DebugTraceTimelineTest {

    @Test
    public void reassemblesThoughtAndGroupsOneCapabilityAcrossPolicyRequestAndVerification() {
        List<DebugTraceWireEvent> events = List.of(
                event(1, DebugTraceWireEvent.PHASE_MODEL_REASONING, "thought", 0, "先检查设备"),
                event(1, DebugTraceWireEvent.PHASE_MODEL_REASONING, "thought", 1, "状态"),
                event(2, DebugTraceWireEvent.PHASE_MODEL_PROPOSED, "plan", 0,
                        "iter=0 finish=TOOL_CALLS toolCalls=1 contentChars=0"),
                event(3, DebugTraceWireEvent.PHASE_POLICY_DECIDED, "policy", 0,
                        "cap=system.volume allowed=true reason=ok"),
                event(4, DebugTraceWireEvent.PHASE_REQUEST_DELIVERED, "request", 0,
                        "cap=system.volume argumentShape={level:number}"),
                event(5, DebugTraceWireEvent.PHASE_DEVICE_VERIFIED, "result", 0,
                        "cap=system.volume status=SUCCESS verified=true durationMs=5"));

        List<DebugTraceTimeline.Node> timeline = DebugTraceTimeline.from(events);

        assertEquals(3, timeline.size());
        assertEquals(DebugTraceTimeline.Kind.THINKING, timeline.get(0).kind);
        assertEquals(DebugTraceTimeline.Kind.PLAN, timeline.get(1).kind);
        assertEquals(DebugTraceTimeline.Kind.TOOL, timeline.get(2).kind);
        assertTrue("verified tool node uses the success color",
                timeline.get(2).statusColorRes() == R.color.matrix_trace_status_verified);
    }

    @Test
    public void policyRejectionFinishesTheToolNodeBeforeTheNextSameCapabilityAttempt() {
        List<DebugTraceWireEvent> events = List.of(
                event(1, DebugTraceWireEvent.PHASE_POLICY_DECIDED, "deny", 0,
                        "cap=system.brightness allowed=false reason=not_allowed"),
                event(2, DebugTraceWireEvent.PHASE_POLICY_DECIDED, "allow", 0,
                        "cap=system.brightness allowed=true reason=ok"),
                event(3, DebugTraceWireEvent.PHASE_DEVICE_VERIFIED, "done", 0,
                        "cap=system.brightness status=SUCCESS verified=true durationMs=5"));

        List<DebugTraceTimeline.Node> timeline = DebugTraceTimeline.from(events);

        assertEquals(2, timeline.size());
        assertEquals(DebugTraceTimeline.Kind.TOOL, timeline.get(0).kind);
        assertEquals(DebugTraceTimeline.Kind.TOOL, timeline.get(1).kind);
        assertTrue("denied node uses the rejection color",
                timeline.get(0).statusColorRes() == R.color.matrix_trace_status_failed);
        assertTrue("second attempt is independently verified",
                timeline.get(1).statusColorRes() == R.color.matrix_trace_status_verified);
    }

    /**
     * 交叉锁定：按 AgentEngine 发射端的同一构造方式（DebugTracePayloads 记号 + 末尾自由
     * 文本）拼装 payload，时间线必须解析出相同的能力分组与状态——两端共享一处契约。
     */
    @Test
    public void hostShapedPayloadsCompileIntoTheSameTimelineFacts() {
        String policyPayload = DebugTracePayloads.capability("system.media.set_volume")
                + " " + DebugTracePayloads.token(DebugTracePayloads.KEY_ALLOWED, true)
                + " reason=写操作需要明确 目标"; // 自由文本含空格，只能作末尾记号
        String verifiedPayload = DebugTracePayloads.capability("system.media.set_volume")
                + " " + DebugTracePayloads.token(DebugTracePayloads.KEY_STATUS, "EXECUTION_FAILED")
                + " " + DebugTracePayloads.token(DebugTracePayloads.KEY_VERIFIED, false)
                + " durationMs=5 observedKeys={volume=java.lang.Integer}";
        List<DebugTraceWireEvent> events = List.of(
                event(1, DebugTraceWireEvent.PHASE_POLICY_DECIDED, "policy", 0, policyPayload),
                event(2, DebugTraceWireEvent.PHASE_REQUEST_DELIVERED, "request", 0,
                        DebugTracePayloads.capability("system.media.set_volume")
                                + " argumentShape={percent=java.lang.Integer}"),
                event(3, DebugTraceWireEvent.PHASE_DEVICE_VERIFIED, "result", 0, verifiedPayload));

        List<DebugTraceTimeline.Node> timeline = DebugTraceTimeline.from(events);

        assertEquals(1, timeline.size());
        assertEquals(DebugTraceTimeline.Kind.TOOL, timeline.get(0).kind);
        // verified=false 且非 SUCCESS：未通过核验的失败色，而不是"进行中"。
        assertTrue(timeline.get(0).statusColorRes() == R.color.matrix_trace_status_failed);
    }

    private static DebugTraceWireEvent event(long sequence, String phase, String traceId,
            int partIndex, String payload) {
        return new DebugTraceWireEvent(sequence, phase, "runtime", traceId, sequence,
                partIndex, 2, payload, "conversation", "task", "user");
    }
}
