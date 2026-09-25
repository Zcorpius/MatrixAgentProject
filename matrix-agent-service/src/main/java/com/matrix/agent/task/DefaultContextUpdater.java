package com.matrix.agent.task;

import com.matrix.agent.session.SessionContext;

import com.matrix.agent.identity.VehicleZone;

import android.util.Log;

import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.identity.*;
import com.matrix.agent.intent.*;
import com.matrix.agent.vehicle.*;
import com.matrix.agent.session.*;
import com.matrix.agent.task.tool.ToolResult;


public final class DefaultContextUpdater implements ContextUpdater {
    private static final String TAG = "MatrixAgent";

    @Override
    public void onToolCompleted(SessionContext context, ToolCall call, ToolResult result) {
        if (!result.isSuccess()) {
            Log.d(TAG, "[Context] skip update cap=" + call.getCapabilityName()
                    + " reason=result not success status=" + result.getStatus());
            return;
        }
        if (result.isVerified() && "vehicle.climate.set_temperature".equals(call.getCapabilityName())) {
            VehicleZone zone = VehicleZone.parse(call.argument("zone"));
            Object temperature = zone == null ? null
                    : result.getObservedState().get(zone.wireValue() + ".temperature");
            if (temperature instanceof Number && zone != null
                    && ((Number) temperature).doubleValue() == Math.rint(((Number) temperature).doubleValue())
                    && ((Number) temperature).intValue() >= 16
                    && ((Number) temperature).intValue() <= 30) {
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
