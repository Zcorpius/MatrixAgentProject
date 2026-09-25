package com.matrix.agent.task.persistence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

import com.matrix.agent.data.memory.EpisodicEventKind;
import com.matrix.agent.data.memory.EpisodicFactCodec;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.task.AgentIteration;
import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.task.TaskState;
import com.matrix.agent.task.ToolObservation;
import com.matrix.agent.task.Trajectory;
import com.matrix.agent.task.tool.ToolResult;

/**
 * Bounded episodic event summary. Version 2 keeps coarse task metadata and at most three
 * verified facts extracted from successful tool readback. Navigation destinations must also
 * appear literally in the current user request. Raw user text, model output, call arguments,
 * result messages and full trajectories are never serialized.
 *
 * <p>Only SUCCEEDED and FAILED tasks are retained. RoomMemoryWriter enforces a 2048-byte
 * payload limit, 30-day retention and an owner/zone scoped 100-row cap.
 */
public final class EpisodicSummary {

    /** 用户指定——summary JSON 字节数上限。 */
    public static final int MAX_SUMMARY_JSON_LENGTH = 2048;
    /** 用户指定——successfulCapabilities dedupe 后最多保留的 capability 数。 */
    public static final int MAX_SUCCESSFUL_CAPABILITIES = 3;

    private static final Set<TaskState> PERSISTED_STATES;
    private static final java.util.regex.Pattern SAFE_CAPABILITY = java.util.regex.Pattern.compile(
            "[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+");
    static {
        // 用户指定:仅 SUCCEEDED / FAILED 写入。
        // 曾擅自加入 PARTIALLY_SUCCEEDED;后续移除——不在用户确认的范围内,
        // 且语义上部分成功不代表完整意图。若用户确认要写入再加回。
        Set<TaskState> states = new LinkedHashSet<>();
        states.add(TaskState.SUCCEEDED);
        states.add(TaskState.FAILED);
        PERSISTED_STATES = Collections.unmodifiableSet(states);
    }

    private final long startedAtMillis;
    private final String finalState;
    private final long durationMs;
    private final int turnCount;
    private final List<String> successfulCapabilities;
    private final String summaryJson;
    private final boolean skip;

    private EpisodicSummary(long startedAtMillis, String finalState, long durationMs,
            int turnCount, List<String> successfulCapabilities, String summaryJson, boolean skip) {
        this.startedAtMillis = startedAtMillis;
        this.finalState = finalState;
        this.durationMs = durationMs;
        this.turnCount = turnCount;
        this.successfulCapabilities = successfulCapabilities;
        this.summaryJson = summaryJson;
        this.skip = skip;
    }

    public long getStartedAtMillis() { return startedAtMillis; }
    public String getFinalState() { return finalState; }
    public long getDurationMs() { return durationMs; }
    public int getTurnCount() { return turnCount; }
    public List<String> getSuccessfulCapabilities() { return successfulCapabilities; }
    public String toJson() { return summaryJson; }
    /** 终态不在 {SUCCEEDED, FAILED} 时 true,RoomMemoryWriter 据此跳过写入。 */
    public boolean shouldSkip() { return skip; }

    /**
     * 从 AgentOutcome 提取安全字段并预序列化 summaryJson。
     *
     * <p>非 persisted 终态 → 返回 shouldSkip=true 的 stub(空 summaryJson),
     * 调用方据此跳过写入。
     */
    public static EpisodicSummary build(AgentRequest request, AgentOutcome outcome) {
        if (request == null || outcome == null) {
            return new EpisodicSummary(0L, "", 0L, 0,
                    Collections.emptyList(), "", true);
        }
        TaskState state = outcome.getFinalState();
        if (state == null || !PERSISTED_STATES.contains(state)) {
            return new EpisodicSummary(0L,
                    state == null ? "" : state.name(),
                    0L, 0, Collections.emptyList(), "", true);
        }
        Trajectory trajectory = outcome.getTrajectory();
        long startedAt = trajectory == null ? 0L : trajectory.getStartedAtMillis();
        int turnCount = trajectory == null ? 0 : trajectory.getIterations().size();
        List<String> capabilities = extractSuccessfulCapabilities(trajectory);
        JSONArray facts = extractVerifiedFacts(request, outcome, capabilities);
        String json = serializeWithSizeCap(EpisodicFactCodec.eventIdFor(request, startedAt), state.name(),
                startedAt, outcome.getDurationMillis(), turnCount, capabilities, facts);
        return new EpisodicSummary(startedAt, state.name(), outcome.getDurationMillis(),
                turnCount, capabilities, json, false);
    }

    /**
     * 遍历 trajectory.iterations,收集 observation.isSuccess() 的 capabilityName,
     * dedupe(LinkedHashSet 保序) + 字典序排序 + ≤3 截断。
     *
     * <p>该列表仅保存 capability 名；核验回读细节由独立白名单提取器处理。
     */
    private static List<String> extractSuccessfulCapabilities(Trajectory trajectory) {
        if (trajectory == null) return Collections.emptyList();
        Set<String> deduped = new LinkedHashSet<>();
        for (AgentIteration iteration : trajectory.getIterations()) {
            for (ToolObservation observation : iteration.getObservations()) {
                String name = observation.getCapabilityName();
                if (observation.isSuccess() && name != null && name.length() <= 64
                        && SAFE_CAPABILITY.matcher(name).matches()
                        && EpisodicEventKind.fromCapability(name) != null) {
                    deduped.add(name);
                }
            }
        }
        List<String> sorted = new ArrayList<>(deduped);
        Collections.sort(sorted);
        if (sorted.size() > MAX_SUCCESSFUL_CAPABILITIES) {
            sorted = new ArrayList<>(sorted.subList(0, MAX_SUCCESSFUL_CAPABILITIES));
        }
        return Collections.unmodifiableList(sorted);
    }

    /**
     * 序列化为紧凑 JSON。若超限先丢弃可选事实；结构元数据始终保留。
     */
    private static String serializeWithSizeCap(String eventId, String finalState, long startedAt,
            long durationMs, int turnCount, List<String> capabilities, JSONArray facts) {
        try {
            String full = serializeJson(eventId, finalState, startedAt, durationMs,
                    turnCount, capabilities, facts);
            if (full.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= MAX_SUMMARY_JSON_LENGTH) return full;
            return serializeJson(eventId, finalState, startedAt, durationMs,
                    turnCount, capabilities, new JSONArray());
        } catch (Exception ex) {
            return "";
        }
    }

    private static String serializeJson(String eventId, String finalState, long startedAt,
            long durationMs, int turnCount, List<String> capabilities, JSONArray facts) throws Exception {
        JSONObject json = new JSONObject();
        json.put("eventSchemaVersion", EpisodicEventKind.SCHEMA_VERSION);
        json.put("eventId", eventId);
        EpisodicEventKind kind = null;
        for (String capability : capabilities) {
            kind = EpisodicEventKind.fromCapability(capability);
            if (kind != null) break;
        }
        json.put("eventKind", kind == null ? JSONObject.NULL : kind.wireValue());
        json.put("startedAtMillis", startedAt);
        json.put("finalState", finalState);
        json.put("durationMs", durationMs);
        json.put("turnCount", turnCount);
        JSONArray caps = new JSONArray();
        for (String cap : capabilities) {
            caps.put(cap);
        }
        json.put("successfulCapabilities", caps);
        json.put("verifiedFacts", facts);
        return json.toString();
    }

    private static JSONArray extractVerifiedFacts(AgentRequest request, AgentOutcome outcome,
            List<String> capabilities) {
        JSONArray facts = new JSONArray();
        for (ToolResult result : outcome.getInternalResults()) {
            if (facts.length() >= EpisodicFactCodec.MAX_FACTS) break;
            if (result == null || !result.isSuccess() || !result.isVerified()) continue;
            String cap = result.getCapabilityName();
            if (!capabilities.contains(cap)) continue;
            java.util.Map<String, Object> observed = result.getObservedState();
            if ("navigation.start_route".equals(cap)) {
                Object raw = observed.get("navigation.destination");
                if (raw instanceof String) {
                    String destination = ((String) raw).trim();
                    if (EpisodicFactCodec.validDestination(destination)
                            && request.getText().contains(destination)) {
                        facts.put(fact("navigation_destination", "global", destination));
                    }
                }
            } else if ("vehicle.climate.set_temperature".equals(cap)) {
                addNumericFact(facts, observed, "temperature", "climate_temperature", 16, 30);
            } else if ("vehicle.seat.set_heating_level".equals(cap)) {
                addNumericFact(facts, observed, "seatHeating", "seat_heating_level", 0, 3);
            } else if ("system.media.set_volume".equals(cap)) {
                addGlobalNumericFact(facts, observed, "media.volume.percent", "media_volume");
            } else if ("system.display.set_brightness".equals(cap)) {
                addGlobalNumericFact(facts, observed, "display.brightness.percent", "display_brightness");
            }
        }
        return facts;
    }

    private static void addNumericFact(JSONArray facts, java.util.Map<String, Object> observed,
            String readbackSuffix, String kind, int min, int max) {
        for (String zone : List.of("driver", "passenger")) {
            Object raw = observed.get(zone + "." + readbackSuffix);
            if (raw instanceof Number) {
                double value = ((Number) raw).doubleValue();
                if (Double.isFinite(value) && value == Math.rint(value)
                        && value >= min && value <= max) {
                    facts.put(fact(kind, zone, (int) value));
                    return;
                }
            }
        }
    }

    private static JSONObject fact(String kind, String zone, Object value) {
        JSONObject fact = new JSONObject();
        try {
            fact.put("kind", kind);
            fact.put("zone", zone);
            fact.put("value", value);
        } catch (Exception invalid) { throw new IllegalStateException(invalid); }
        return fact;
    }

    private static void addGlobalNumericFact(JSONArray facts,
            java.util.Map<String, Object> observed, String readbackKey, String kind) {
        Object raw = observed.get(readbackKey);
        if (!(raw instanceof Number)) return;
        double value = ((Number) raw).doubleValue();
        if (Double.isFinite(value) && value == Math.rint(value)
                && value >= 0 && value <= 100) {
            facts.put(fact(kind, "global", (int) value));
        }
    }
}
