package com.matrix.agent.task.persistence;

import android.util.Log;

import com.matrix.agent.contract.AgentMessage;
import com.matrix.agent.task.AgentIteration;
import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.task.StopReason;
import com.matrix.agent.task.TaskState;
import com.matrix.agent.task.ToolObservation;
import com.matrix.agent.task.Trajectory;
import com.matrix.agent.task.policy.PolicyDecision;
import com.matrix.agent.task.tool.ToolResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Trajectory ↔ JSON 序列化——纯 Java 工具,无 Android 依赖。
 *
 * <p>序列化的对象在 AgentEngine 内部已 auditRedact 字段级脱敏,可安全落盘。
 * 反向解码还原 Trajectory 等价结构(供 episodic memory 召回 / 调试回放使用)。
 *
 * <p>职责收敛:本类**仅 RoomAuditRepository 使用**(audit 表
 * 已有 PII redact 兜底,且 audit 列原本就设计为完整 trajectory)。Episodic 写入路径
 * (session_history 表)改用 {@link com.matrix.agent.task.persistence.EpisodicSummary}——
 * 不再保留 userText / assistantContent / tool arguments / tool result,避免 PII 回声。
 *
 * <p>已知限制:
 * <ul>
 *   <li>ToolCallSnapshot.arguments 的 value 类型:Integer/Double/Boolean 在 round-trip 后
 *       可能变成 Long/Double/Boolean(org.json 行为)。引入 schema-aware codec 后修复。</li>
 *   <li>ToolResult.observedState 同上。</li>
 * </ul>
 */
public final class TrajectoryCodec {
    private static final String TAG = "MatrixAgent";

    private TrajectoryCodec() {}

    /** 把 AgentOutcome 序列化为 trajectoryJson 字段值。 */
    public static String encode(AgentOutcome outcome) {
        if (outcome == null) return "";
        try {
            JSONObject root = new JSONObject();
            root.put("requestId", outcome.getRequestId());
            root.put("finalState", outcome.getFinalState().name());
            root.put("stopReason", outcome.getStopReason().name());
            root.put("durationMillis", outcome.getDurationMillis());

            Trajectory trajectory = outcome.getTrajectory();
            JSONObject trajectoryJson = new JSONObject();
            trajectoryJson.put("startedAtMillis", trajectory.getStartedAtMillis());
            trajectoryJson.put("durationMillis", trajectory.getDurationMillis());
            trajectoryJson.put("totalToolCalls", trajectory.getTotalToolCalls());
            trajectoryJson.put("stopReason",
                    trajectory.getStopReason() == null ? "" : trajectory.getStopReason().name());

            JSONArray iterations = new JSONArray();
            for (AgentIteration iteration : trajectory.getIterations()) {
                iterations.put(encodeIteration(iteration));
            }
            trajectoryJson.put("iterations", iterations);
            root.put("trajectory", trajectoryJson);
            return root.toString();
        } catch (Exception ex) {
            Log.e(TAG, "[Codec] encode failed req=" + outcome.getRequestId()
                    + " cause=" + ex.getClass().getSimpleName(), ex);
            return "";
        }
    }

    private static JSONObject encodeIteration(AgentIteration iteration) throws Exception {
        JSONObject json = new JSONObject();
        json.put("iteration", iteration.getIteration());
        json.put("durationMillis", iteration.getDurationMillis());

        AgentMessage assistant = iteration.getAssistantMessage();
        JSONObject assistantJson = new JSONObject();
        assistantJson.put("role", assistant.getRole().name());
        assistantJson.put("content", assistant.getContent() == null ? "" : assistant.getContent());
        json.put("assistant", assistantJson);

        JSONArray toolCalls = new JSONArray();
        for (AgentIteration.ToolCallSnapshot snapshot : iteration.getToolCalls()) {
            JSONObject callJson = new JSONObject();
            callJson.put("toolCallId", snapshot.getToolCallId());
            callJson.put("capabilityName", snapshot.getCapabilityName());
            callJson.put("arguments", encodeArguments(snapshot.getArguments()));
            toolCalls.put(callJson);
        }
        json.put("toolCalls", toolCalls);

        JSONArray observations = new JSONArray();
        for (ToolObservation observation : iteration.getObservations()) {
            observations.put(encodeObservation(observation));
        }
        json.put("observations", observations);

        JSONArray decisions = new JSONArray();
        for (PolicyDecision decision : iteration.getPolicyDecisions()) {
            JSONObject decisionJson = new JSONObject();
            decisionJson.put("allowed", decision.isAllowed());
            decisionJson.put("reason", decision.getReason());
            decisionJson.put("rejectionType",
                    decision.getRejectionType() == null ? "" : decision.getRejectionType().name());
            decisions.put(decisionJson);
        }
        json.put("policyDecisions", decisions);

        return json;
    }

    private static JSONObject encodeObservation(ToolObservation observation) throws Exception {
        JSONObject json = new JSONObject();
        json.put("toolCallId", observation.getToolCallId());
        json.put("capabilityName", observation.getCapabilityName());
        json.put("capabilityBlocked", observation.isCapabilityBlocked());
        if (observation.getRejectionReason() != null) {
            json.put("rejectionReason", observation.getRejectionReason());
        }
        ToolResult result = observation.getResult();
        if (result != null) {
            JSONObject resultJson = new JSONObject();
            resultJson.put("status", result.getStatus().name());
            resultJson.put("capabilityName", result.getCapabilityName());
            resultJson.put("message", result.getMessage() == null ? "" : result.getMessage());
            resultJson.put("verified", result.isVerified());
            resultJson.put("durationMillis", result.getDurationMillis());
            resultJson.put("observedState", encodeArguments(result.getObservedState()));
            json.put("result", resultJson);
        }
        return json;
    }

    private static JSONObject encodeArguments(Map<String, Object> arguments) throws Exception {
        JSONObject json = new JSONObject();
        if (arguments == null) return json;
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            Object value = entry.getValue();
            if (value == null) continue;
            json.put(entry.getKey(), value);
        }
        return json;
    }

    /**
     * 反向解码:JSON → Trajectory(等价结构)。
     * 不还原完整 AgentOutcome(internalResults 不可逆),仅 trajectory 视图。
     */
    public static Trajectory decodeTrajectory(String json) {
        if (json == null || json.isEmpty()) return new Trajectory();
        try {
            JSONObject root = new JSONObject(json);
            JSONObject trajectoryJson = root.optJSONObject("trajectory");
            if (trajectoryJson == null) return new Trajectory();
            long startedAt = trajectoryJson.optLong("startedAtMillis", System.currentTimeMillis());
            Trajectory trajectory = new Trajectory(startedAt);
            JSONArray iterations = trajectoryJson.optJSONArray("iterations");
            if (iterations != null) {
                for (int i = 0; i < iterations.length(); i++) {
                    AgentIteration iteration = decodeIteration(iterations.getJSONObject(i));
                    if (iteration != null) trajectory.addIteration(iteration);
                }
            }
            String stopReasonName = trajectoryJson.optString("stopReason", "");
            if (!stopReasonName.isEmpty()) {
                StopReason stopReason = StopReason.valueOf(stopReasonName);
                trajectory.finish(stopReason,
                        trajectoryJson.optLong("durationMillis", 0L),
                        trajectoryJson.optInt("totalToolCalls", 0));
            }
            return trajectory;
        } catch (Exception ex) {
            Log.e(TAG, "[Codec] decodeTrajectory failed cause=" + ex.getClass().getSimpleName(), ex);
            return new Trajectory();
        }
    }

    private static AgentIteration decodeIteration(JSONObject json) throws Exception {
        int iterationNo = json.optInt("iteration", 1);
        long durationMs = json.optLong("durationMillis", 0L);
        JSONObject assistantJson = json.optJSONObject("assistant");
        AgentMessage assistant = null;
        if (assistantJson != null) {
            String content = assistantJson.optString("content", "");
            assistant = AgentMessage.assistant(content, Collections.emptyList());
        } else {
            assistant = AgentMessage.assistant("", Collections.emptyList());
        }
        return new AgentIteration(iterationNo, assistant,
                decodeToolCalls(json.optJSONArray("toolCalls")),
                decodeObservations(json.optJSONArray("observations")),
                decodeDecisions(json.optJSONArray("policyDecisions")),
                durationMs);
    }

    private static List<AgentIteration.ToolCallSnapshot> decodeToolCalls(JSONArray array)
            throws Exception {
        if (array == null) return Collections.emptyList();
        List<AgentIteration.ToolCallSnapshot> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject json = array.getJSONObject(i);
            result.add(new AgentIteration.ToolCallSnapshot(
                    json.optString("toolCallId"),
                    json.optString("capabilityName"),
                    decodeArguments(json.optJSONObject("arguments"))));
        }
        return result;
    }

    private static List<ToolObservation> decodeObservations(JSONArray array) throws Exception {
        if (array == null) return Collections.emptyList();
        List<ToolObservation> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject json = array.getJSONObject(i);
            String toolCallId = json.optString("toolCallId");
            String capabilityName = json.optString("capabilityName");
            boolean capabilityBlocked = json.optBoolean("capabilityBlocked");
            String rejectionReason = json.optString("rejectionReason", null);
            JSONObject resultJson = json.optJSONObject("result");
            if (rejectionReason != null && !rejectionReason.isEmpty()) {
                result.add(ToolObservation.fromParts(toolCallId, capabilityName,
                        null, rejectionReason, capabilityBlocked));
            } else if (resultJson != null) {
                ToolResult toolResult = new ToolResult(
                        ToolResult.Status.valueOf(resultJson.optString("status")),
                        resultJson.optString("capabilityName"),
                        resultJson.optString("message"),
                        decodeArguments(resultJson.optJSONObject("observedState")),
                        resultJson.optBoolean("verified"),
                        resultJson.optLong("durationMillis"));
                result.add(ToolObservation.fromParts(toolCallId, capabilityName,
                        toolResult, null, capabilityBlocked));
            }
        }
        return result;
    }

    private static List<PolicyDecision> decodeDecisions(JSONArray array) throws Exception {
        if (array == null) return Collections.emptyList();
        List<PolicyDecision> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject json = array.getJSONObject(i);
            boolean allowed = json.optBoolean("allowed");
            String reason = json.optString("reason", "");
            String rejectionTypeName = json.optString("rejectionType", "");
            if (allowed) {
                result.add(PolicyDecision.allow());
            } else if ("CAPABILITY".equals(rejectionTypeName)) {
                result.add(PolicyDecision.denyCapability(reason));
            } else {
                result.add(PolicyDecision.denyParameter(reason));
            }
        }
        return result;
    }

    private static Map<String, Object> decodeArguments(JSONObject json) {
        if (json == null) return Collections.emptyMap();
        Map<String, Object> result = new LinkedHashMap<>();
        JSONArray keys = json.names();
        if (keys == null) return result;
        for (int i = 0; i < keys.length(); i++) {
            String key = keys.optString(i);
            result.put(key, json.opt(key));
        }
        return result;
    }
}
