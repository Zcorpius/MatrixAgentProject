package com.matrix.agent.launcher.presentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.api.debug.DebugTraceWireEvent;

import org.junit.Test;

import java.util.List;

/** Locks down the Operit-style grouping contract without requiring an Android View hierarchy. */
public final class DebugTraceTimelineTest {

    @Test
    public void reassemblesThoughtAndGroupsOneCapabilityAcrossPolicyRequestAndVerification() {
        List<DebugTraceWireEvent> events = List.of(
                event(1, "MODEL_REASONING", "thought", 0, "先检查设备"),
                event(1, "MODEL_REASONING", "thought", 1, "状态"),
                event(2, "MODEL_PROPOSED", "plan", 0,
                        "iter=0 finish=TOOL_CALLS toolCalls=1 contentChars=0"),
                event(3, "POLICY_DECIDED", "policy", 0,
                        "cap=system.volume allowed=true reason=ok"),
                event(4, "REQUEST_DELIVERED", "request", 0,
                        "cap=system.volume argumentShape={level:number}"),
                event(5, "DEVICE_VERIFIED", "result", 0,
                        "cap=system.volume status=SUCCESS verified=true durationMs=5"));

        List<DebugTraceTimeline.Node> timeline = DebugTraceTimeline.from(events);

        assertEquals(3, timeline.size());
        assertEquals(DebugTraceTimeline.Kind.THINKING, timeline.get(0).kind);
        assertEquals(DebugTraceTimeline.Kind.PLAN, timeline.get(1).kind);
        assertEquals(DebugTraceTimeline.Kind.TOOL, timeline.get(2).kind);
        assertTrue("verified tool node uses the success color",
                timeline.get(2).statusColor() == 0xFF3C8A69);
    }

    @Test
    public void policyRejectionFinishesTheToolNodeBeforeTheNextSameCapabilityAttempt() {
        List<DebugTraceWireEvent> events = List.of(
                event(1, "POLICY_DECIDED", "deny", 0,
                        "cap=system.brightness allowed=false reason=not_allowed"),
                event(2, "POLICY_DECIDED", "allow", 0,
                        "cap=system.brightness allowed=true reason=ok"),
                event(3, "DEVICE_VERIFIED", "done", 0,
                        "cap=system.brightness status=SUCCESS verified=true durationMs=5"));

        List<DebugTraceTimeline.Node> timeline = DebugTraceTimeline.from(events);

        assertEquals(2, timeline.size());
        assertEquals(DebugTraceTimeline.Kind.TOOL, timeline.get(0).kind);
        assertEquals(DebugTraceTimeline.Kind.TOOL, timeline.get(1).kind);
        assertTrue("denied node uses the rejection color",
                timeline.get(0).statusColor() == 0xFFB65B5B);
        assertTrue("second attempt is independently verified",
                timeline.get(1).statusColor() == 0xFF3C8A69);
    }

    private static DebugTraceWireEvent event(long sequence, String phase, String traceId,
            int partIndex, String payload) {
        return new DebugTraceWireEvent(sequence, phase, "runtime", traceId, sequence,
                partIndex, 2, payload, "conversation", "task", "user");
    }
}
