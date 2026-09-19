package com.matrix.agent.platform.control;

import android.util.Log;

import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.task.capability.CapabilityProvider;
import com.matrix.agent.task.tool.ToolResult;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Executes only the explicitly routed, non-vehicle Android system-control capabilities. */
public final class SystemControlCapabilityProvider implements CapabilityProvider {
    public static final String MEDIA_VOLUME = "system.media.set_volume";
    public static final String SCREEN_BRIGHTNESS = "system.display.set_brightness";
    private static final String TAG = "MatrixAgent";
    private static final Set<String> CAPABILITIES = Set.of(MEDIA_VOLUME, SCREEN_BRIGHTNESS);

    private final SystemControlPort controls;

    public SystemControlCapabilityProvider(SystemControlPort controls) {
        this.controls = controls;
    }

    public static boolean handles(String capabilityName) {
        return CAPABILITIES.contains(capabilityName);
    }

    @Override public ToolResult execute(AgentRequest request, ToolCall call) {
        String capability = call.getCapabilityName();
        if (!handles(capability)) {
            return new ToolResult(ToolResult.Status.EXECUTION_FAILED, capability,
                    "unsupported_system_control", Collections.emptyMap(), false, 0L);
        }
        long started = System.nanoTime();
        Object rawPercent = call.argument("percent");
        if (!(rawPercent instanceof Number)) {
            return failed(capability, "missing_percent", started);
        }
        double value = ((Number) rawPercent).doubleValue();
        if (!Double.isFinite(value) || value != Math.rint(value)) {
            return failed(capability, "invalid_percent", started);
        }
        int percent = (int) value;
        SystemControlPort.ControlResult result = MEDIA_VOLUME.equals(capability)
                ? controls.setMediaVolumePercent(percent)
                : controls.setScreenBrightnessPercent(percent);
        if (!result.accepted) {
            Log.w(TAG, "[SystemControl] rejected capability=" + capability
                    + " reason=" + result.diagnostic);
            return failed(capability, result.diagnostic, started);
        }
        Map<String, Object> readback = new LinkedHashMap<>();
        readback.put(MEDIA_VOLUME.equals(capability) ? "media.volume.percent"
                : "display.brightness.percent", result.actualPercent);
        String label = MEDIA_VOLUME.equals(capability) ? "媒体音量" : "屏幕亮度";
        return new ToolResult(result.verified ? ToolResult.Status.SUCCESS
                : ToolResult.Status.VERIFICATION_FAILED, capability,
                label + "已设置为 " + result.actualPercent + "%", readback, result.verified,
                elapsedMillis(started));
    }

    private static ToolResult failed(String capability, String reason, long started) {
        return new ToolResult(ToolResult.Status.EXECUTION_FAILED, capability,
                "系统设置未生效：" + reason, Collections.emptyMap(), false, elapsedMillis(started));
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }
}
