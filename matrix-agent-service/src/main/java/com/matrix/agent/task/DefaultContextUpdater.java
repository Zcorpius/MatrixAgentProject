package com.matrix.agent.task;

import android.util.Log;

import com.matrix.agent.task.identity.*;
import com.matrix.agent.data.session.*;
import com.matrix.agent.task.tool.*;


public final class DefaultContextUpdater implements ContextUpdater {
    private static final String TAG = "MatrixAgent";

    @Override
    public void onToolCompleted(SessionContext context, ToolCall call, ToolResult result) {
        if (!result.isSuccess()) {
            Log.d(TAG, "[Context] skip update cap=" + call.getCapabilityName()
                    + " reason=result not success status=" + result.getStatus());
            return;
        }
        if ("vehicle.climate.set_temperature".equals(call.getCapabilityName())) {
            Object temperature = call.argument("temperature");
            VehicleZone zone = VehicleZone.parse(call.argument("zone"));
            if (temperature instanceof Number && zone != null) {
                int tempValue = ((Number) temperature).intValue();
                context.rememberTemperature(tempValue, zone.wireValue());
                Log.d(TAG, "[Context] remembered temperature=" + tempValue
                        + " zone=" + zone.wireValue());
            } else {
                Log.w(TAG, "[Context] climate success but missing temperature/zone"
                        + " temp=" + temperature + " zone=" + zone);
            }
        }
    }
}
