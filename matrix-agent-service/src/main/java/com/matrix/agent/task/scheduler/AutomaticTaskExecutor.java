package com.matrix.agent.task.scheduler;

import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.identity.*;
import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.task.AgentRuntimeRepository;
import com.matrix.agent.task.capability.*;
import com.matrix.agent.task.policy.PolicyEngine;
import com.matrix.agent.task.tool.*;
import java.util.Map;

/** Existing policy/provider/tool boundaries reused by both deterministic and model-backed actions. */
public final class AutomaticTaskExecutor {
    private final AgentRuntimeRepository runtime;
    private final CapabilityRegistry registry;
    private final CapabilityProvider provider;
    private final PolicyEngine policy;
    private final ToolExecutor tools;
    public AutomaticTaskExecutor(AgentRuntimeRepository runtime, CapabilityRegistry registry,
            CapabilityProvider provider, PolicyEngine policy, ToolExecutor tools) {
        this.runtime = runtime; this.registry = registry; this.provider = provider; this.policy = policy; this.tools = tools;
    }
    public boolean readOnly(java.util.Collection<String> capabilities) {
        for (String name : capabilities) { var definition = registry.find(name); if (definition == null || definition.isWriteOperation()) return false; }
        return true;
    }
    public void validate(java.util.Collection<String> capabilities) {
        for (String name : capabilities) if (registry.find(name) == null) throw new IllegalArgumentException("能力不可用：" + name);
    }
    public AutoCloseable tryModelLease(CancellationToken token, boolean readOnly) throws InterruptedException { return runtime.tryAutomaticLease(token, readOnly); }
    public AgentOutcome agent(PreparedAutomaticTask task, CancellationToken token) { return runtime.executeAutomatic(task, token); }
    public ToolResult tool(PreparedAutomaticTask task, String capability, Map<String, Object> arguments, CancellationToken token) {
        validate(java.util.List.of(capability));
        AgentRequest request = runtime.prepareAutomaticRequest(task, token);
        ToolCall call = ToolCall.withId(task.runtimeRequestId(), capability, arguments);
        var decision = policy.evaluate(request, call);
        if (!decision.isAllowed()) return ToolResult.rejected(capability, decision.getReason());
        return tools.execute(provider, registry.find(capability), request, call);
    }
}
