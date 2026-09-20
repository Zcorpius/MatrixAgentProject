package com.matrix.agent.conversation;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 能力事实轨迹编解码（评估 v1.0 §4.3）：{@code List<CapabilityExecutionTrace>} ↔
 * {@code conversation_task_link.execution_trace_json}。版本 1；畸形输入 fail-closed
 * 抛 IllegalArgumentException，绝不静默截断事实。
 */
public final class CapabilityTraceCodec {

    public static final int VERSION = 1;

    private CapabilityTraceCodec() {
    }

    public static String encode(List<CapabilityExecutionTrace> traces) {
        JSONArray array = new JSONArray();
        for (CapabilityExecutionTrace trace : traces) {
            JSONObject item = new JSONObject();
            try {
                item.put("c", trace.capabilityId);
                item.put("f", trace.friendlyName);
                item.put("o", trace.outcome);
                item.put("r", trace.requestedDisplay == null ? JSONObject.NULL
                        : trace.requestedDisplay);
                item.put("v", trace.verifiedDisplay == null ? JSONObject.NULL
                        : trace.verifiedDisplay);
                item.put("s", trace.verificationState);
                array.put(item);
            } catch (JSONException impossible) {
                throw new IllegalStateException("trace encode failed", impossible);
            }
        }
        return array.toString();
    }

    public static List<CapabilityExecutionTrace> decode(String json) {
        List<CapabilityExecutionTrace> traces = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return traces;
        }
        JSONArray array;
        try {
            array = new JSONArray(json);
        } catch (JSONException invalid) {
            throw new IllegalArgumentException("trace json 畸形", invalid);
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) {
                throw new IllegalArgumentException("trace 条目畸形 @" + i);
            }
            traces.add(new CapabilityExecutionTrace(
                    item.optString("c", null),
                    item.optString("f", null),
                    item.optString("o", null),
                    item.isNull("r") ? null : item.optString("r", null),
                    item.isNull("v") ? null : item.optString("v", null),
                    item.optString("s", null)));
        }
        return traces;
    }
}
