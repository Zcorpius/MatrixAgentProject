package com.matrix.agent.platform.control;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.task.tool.ToolResult;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/** Provider contract: write requests remain bounded and must report device readback. */
public final class SystemControlCapabilityProviderTest {
    @Test public void mediaVolumeReturnsVerifiedReadback() {
        FakeControls controls = new FakeControls();
        controls.volumeResult = SystemControlPort.ControlResult.readback(35, 35, true);
        ToolResult result = provider(controls).execute(request(), call(
                SystemControlCapabilityProvider.MEDIA_VOLUME, 35));

        assertEquals(35, controls.lastVolumePercent);
        assertEquals(ToolResult.Status.SUCCESS, result.getStatus());
        assertTrue(result.isVerified());
        assertEquals(35, result.getObservedState().get("media.volume.percent"));
    }

    @Test public void brightnessReportsVerificationFailureInsteadOfClaimingSuccess() {
        FakeControls controls = new FakeControls();
        controls.brightnessResult = SystemControlPort.ControlResult.readback(45, 44, false);
        ToolResult result = provider(controls).execute(request(), call(
                SystemControlCapabilityProvider.SCREEN_BRIGHTNESS, 45));

        assertEquals(45, controls.lastBrightnessPercent);
        assertEquals(ToolResult.Status.VERIFICATION_FAILED, result.getStatus());
        assertFalse(result.isVerified());
        assertEquals(44, result.getObservedState().get("display.brightness.percent"));
    }

    @Test public void rejectedPlatformWriteDoesNotExposeSuccessState() {
        FakeControls controls = new FakeControls();
        controls.volumeResult = SystemControlPort.ControlResult.rejected(20,
                "audio_permission_denied");
        ToolResult result = provider(controls).execute(request(), call(
                SystemControlCapabilityProvider.MEDIA_VOLUME, 20));

        assertEquals(ToolResult.Status.EXECUTION_FAILED, result.getStatus());
        assertFalse(result.isVerified());
        assertTrue(result.getObservedState().isEmpty());
    }

    private static SystemControlCapabilityProvider provider(FakeControls controls) {
        return new SystemControlCapabilityProvider(controls);
    }

    private static AgentRequest request() {
        return AgentRequest.builder("设置系统", Actor.DRIVER).build();
    }

    private static ToolCall call(String capability, int percent) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("percent", percent);
        return new ToolCall(capability, args);
    }

    private static final class FakeControls implements SystemControlPort {
        int lastVolumePercent = -1;
        int lastBrightnessPercent = -1;
        ControlResult volumeResult = ControlResult.rejected(-1, "not_configured");
        ControlResult brightnessResult = ControlResult.rejected(-1, "not_configured");

        @Override public ControlResult setMediaVolumePercent(int percent) {
            lastVolumePercent = percent;
            return volumeResult;
        }

        @Override public ControlResult setScreenBrightnessPercent(int percent) {
            lastBrightnessPercent = percent;
            return brightnessResult;
        }
    }
}
