package com.matrix.agent.task.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.identity.RuntimeProfile;
import com.matrix.agent.task.capability.CapabilityRegistry;
import com.matrix.agent.task.capability.MediaCapabilities;
import com.matrix.agent.vehicle.VehicleState;

import org.junit.Test;

import java.util.Map;

public final class MediaUsagePolicyTest {
    private final PolicyEngine policy = new PolicyEngine(CapabilityRegistry.createRuntimeRegistry());
    private final ToolCall openVideo = new ToolCall(MediaCapabilities.BILI_OPEN,
            Map.of("bvid", "BV1xx411c7mD"));

    @Test public void phoneDoesNotReadUnavailableCarStateForVideo() {
        assertTrue(policy.evaluate(request(RuntimeProfile.PHONE), openVideo).isAllowed());
    }

    @Test public void automotiveWithoutTrustedParkingIsCapabilityRejected() {
        PolicyDecision decision = policy.evaluate(request(RuntimeProfile.AUTOMOTIVE), openVideo);
        assertFalse(decision.isAllowed());
        assertEquals(PolicyDecision.RejectionType.CAPABILITY, decision.getRejectionType());
    }

    @Test public void unknownProfileFailsClosedForVideo() {
        assertFalse(policy.evaluate(request(RuntimeProfile.UNKNOWN), openVideo).isAllowed());
    }

    private static AgentRequest request(RuntimeProfile profile) {
        return AgentRequest.builder("打开B站视频 BV1xx411c7mD", Actor.DRIVER)
                .runtimeProfile(profile)
                .vehicleState(VehicleState.unavailable())
                .build();
    }
}
