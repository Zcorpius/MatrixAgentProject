package com.matrix.agent.task;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

import com.matrix.agent.task.identity.AgentRequest;

/**
 * Episodic 写入的安全摘要值对象——替代 {@code TrajectoryCodec.encode(outcome)}。
 *
 * <p>问题背景:此前把 {@code TrajectoryCodec.encode(outcome)} 整段 JSON 塞进
 * {@code session_history.trajectoryJson} 列——完整 requestId / iterations / assistant.content /
 * tool arguments / tool observation result 全部落表。完整用户原文 echo、地址/电话 PII、
 * 命令执行结果,既无大小限制也无脱敏策略。
 *
 * <p>用户硬约束:"不要直接复用完整 TrajectoryCodec。新增 EpisodicSummary... 仅记录
 * 时间/类别/终态/持续时间/成功的能力名称/有限的非敏感结果标签。限制在 1-2KB,最多 3 个能力。
 * 仅记录 SUCCEEDED、FAILED... 取消和超时默认不写"。
 *
 * <p>本类字段(无 PII):
 * <ul>
 *   <li>{@code startedAtMillis} —— Trajectory.getStartedAtMillis,数字</li>
 *   <li>{@code finalState} —— SUCCEEDED / FAILED / PARTIALLY_SUCCEEDED 等枚举名</li>
 *   <li>{@code durationMs} —— AgentOutcome.getDurationMillis,数字</li>
 *   <li>{@code turnCount} —— Trajectory.iterations.size,数字</li>
 *   <li>{@code successfulCapabilities} —— 从 observations 收集 isSuccess() 的 capabilityName,
 *       dedupe + 排序 + ≤3 截断。**仅 capability 名,不含 arguments / result content**</li>
 * </ul>
 *
 * <p><b>完全不读</b>:userText / assistant.content / tool arguments / tool result content /
 * stopMessage(可能含 PII 回声)。
 *
 * <p><b>终态过滤</b>:仅 SUCCEEDED / FAILED 进入 build 路径,
 * CANCELLED / TIMED_OUT / PREEMPTED / REJECTED / PROTOCOL_ERROR / EXECUTION_UNKNOWN / DEFERRED
 * 等其他终态全部 shouldSkip=true,RoomMemoryWriter 不写新行。用户指定——这些终态对 Episodic 召回
 * 价值低,且 CANCELLED 可能含用户隐私意图("取消去医院的导航")。
 *
 * <p>PARTIALLY_SUCCEEDED 也归 shouldSkip=true。原计划与用户确认的是仅
 * SUCCEEDED / FAILED,实现时擅自加入 PARTIALLY_SUCCEEDED(语义上部分成功不代表完整意图,
 * 且 finalState 字段会让模型误判为"已执行"参考)。若用户确认要写入再加回。
 *
 * <p><b>大小限制</b>:summaryJson.length() ≤ 2048(2KB)——超长截断 successfulCapabilities
 * 至 0 个,仍超长则返回最小 stub。EpisodicMemorySourceImpl.toSnippets 不读 trajectoryJson
 * (key 是 session#shortId:finalState),所以截断不影响召回。
 *
 * <p>值对象 + 静态工厂:无状态、无副作用,与 TrajectoryCodec.encode 同模式。
 */
public final class EpisodicSummary {

    /** 用户指定——summary JSON 字节数上限。 */
    public static final int MAX_SUMMARY_JSON_LENGTH = 2048;
    /** 用户指定——successfulCapabilities dedupe 后最多保留的 capability 数。 */
    public static final int MAX_SUCCESSFUL_CAPABILITIES = 3;

    private static final Set<TaskState> PERSISTED_STATES;
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
        String json = serializeWithSizeCap(state.name(), startedAt,
                outcome.getDurationMillis(), turnCount, capabilities);
        return new EpisodicSummary(startedAt, state.name(), outcome.getDurationMillis(),
                turnCount, capabilities, json, false);
    }

    /**
     * 遍历 trajectory.iterations,收集 observation.isSuccess() 的 capabilityName,
     * dedupe(LinkedHashSet 保序) + 字典序排序 + ≤3 截断。
     *
     * <p>**仅 capability 名**——不读 observation.getResult().getObservedState() / getArguments()。
     */
    private static List<String> extractSuccessfulCapabilities(Trajectory trajectory) {
        if (trajectory == null) return Collections.emptyList();
        Set<String> deduped = new LinkedHashSet<>();
        for (AgentIteration iteration : trajectory.getIterations()) {
            for (ToolObservation observation : iteration.getObservations()) {
                if (observation.isSuccess() && observation.getCapabilityName() != null) {
                    deduped.add(observation.getCapabilityName());
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
     * 序列化为紧凑 JSON。若超 {@link #MAX_SUMMARY_JSON_LENGTH},逐步降级:
     * (1) 清空 successfulCapabilities;(2) 仍超长则返回最小 stub(仅 finalState + durationMs)。
     */
    private static String serializeWithSizeCap(String finalState, long startedAt,
            long durationMs, int turnCount, List<String> capabilities) {
        try {
            String full = serializeJson(finalState, startedAt, durationMs, turnCount, capabilities);
            if (full.length() <= MAX_SUMMARY_JSON_LENGTH) return full;
            // 降级 1:清空 capabilities
            String trimmed = serializeJson(finalState, startedAt, durationMs, turnCount,
                    Collections.emptyList());
            if (trimmed.length() <= MAX_SUMMARY_JSON_LENGTH) return trimmed;
            // 降级 2:最小 stub
            JSONObject stub = new JSONObject();
            stub.put("finalState", finalState);
            stub.put("durationMs", durationMs);
            stub.put("truncated", true);
            return stub.toString();
        } catch (Exception ex) {
            // 理论上不会发生(org.json 仅在 null key / NaN 时抛),保守降级到 stub 字符串
            return "{\"finalState\":\"" + finalState + "\",\"truncated\":true}";
        }
    }

    private static String serializeJson(String finalState, long startedAt,
            long durationMs, int turnCount, List<String> capabilities) throws Exception {
        JSONObject json = new JSONObject();
        json.put("startedAtMillis", startedAt);
        json.put("finalState", finalState);
        json.put("durationMs", durationMs);
        json.put("turnCount", turnCount);
        JSONArray caps = new JSONArray();
        for (String cap : capabilities) {
            caps.put(cap);
        }
        json.put("successfulCapabilities", caps);
        return json.toString();
    }
}
