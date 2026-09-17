package com.matrix.agent.task;

import com.matrix.agent.task.policy.PolicyDecision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Agent Loop 单轮记录,用于 Trajectory 回放和评估。 */
public final class AgentIteration {
    private final int iteration;
    private final AgentMessage assistantMessage;
    private final List<ToolCallSnapshot> toolCalls;
    private final List<ToolObservation> observations;
    private final List<PolicyDecision> policyDecisions;
    private final long durationMillis;

    public AgentIteration(int iteration, AgentMessage assistantMessage,
            List<ToolCallSnapshot> toolCalls,
            List<ToolObservation> observations,
            List<PolicyDecision> policyDecisions,
            long durationMillis) {
        if (iteration <= 0) throw new IllegalArgumentException("iteration 必须大于 0");
        if (assistantMessage == null) throw new IllegalArgumentException("assistantMessage 不能为空");
        this.iteration = iteration;
        this.assistantMessage = assistantMessage;
        this.toolCalls = Collections.unmodifiableList(new ArrayList<>(toolCalls));
        this.observations = Collections.unmodifiableList(new ArrayList<>(observations));
        this.policyDecisions = Collections.unmodifiableList(new ArrayList<>(policyDecisions));
        this.durationMillis = durationMillis;
    }

    public int getIteration() { return iteration; }
    public AgentMessage getAssistantMessage() { return assistantMessage; }
    public List<ToolCallSnapshot> getToolCalls() { return toolCalls; }
    public List<ToolObservation> getObservations() { return observations; }
    public List<PolicyDecision> getPolicyDecisions() { return policyDecisions; }
    public long getDurationMillis() { return durationMillis; }

    /** 工具调用快照:capability + arguments + 关联的 PolicyDecision 和 Observation。 */
    public static final class ToolCallSnapshot {
        private final String toolCallId;
        private final String capabilityName;
        private final java.util.Map<String, Object> arguments;

        public ToolCallSnapshot(String toolCallId, String capabilityName,
                java.util.Map<String, Object> arguments) {
            this.toolCallId = toolCallId;
            this.capabilityName = capabilityName;
            this.arguments = arguments == null
                    ? java.util.Collections.emptyMap()
                    : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(arguments));
        }

        public String getToolCallId() { return toolCallId; }
        public String getCapabilityName() { return capabilityName; }
        public java.util.Map<String, Object> getArguments() { return arguments; }
    }
}
