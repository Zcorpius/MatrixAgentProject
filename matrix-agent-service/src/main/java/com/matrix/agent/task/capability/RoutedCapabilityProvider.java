package com.matrix.agent.task.capability;

import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.task.tool.CommandState;
import com.matrix.agent.task.tool.ToolResult;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Routes a closed set of capabilities to platform adapters, with a legacy/domain fallback. */
public final class RoutedCapabilityProvider implements CapabilityProvider {
    private final CapabilityProvider fallback;
    private final Map<String, CapabilityProvider> routes;

    public RoutedCapabilityProvider(CapabilityProvider fallback,
            Map<String, CapabilityProvider> explicitRoutes) {
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        Map<String, CapabilityProvider> copy = new LinkedHashMap<>();
        for (Map.Entry<String, CapabilityProvider> entry : explicitRoutes.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isEmpty()) {
                throw new IllegalArgumentException("route capability name required");
            }
            copy.put(entry.getKey(), Objects.requireNonNull(entry.getValue(), "route provider"));
        }
        routes = Collections.unmodifiableMap(copy);
    }

    @Override public ToolResult execute(AgentRequest request, ToolCall call) {
        CapabilityProvider target = routes.get(call.getCapabilityName());
        return (target == null ? fallback : target).execute(request, call);
    }

    @Override public boolean isAbortable() { return false; }

    @Override public void abortIfSupported(String commandId) { }

    @Override public CommandState queryCommandState(String commandId) {
        return CommandState.UNKNOWN;
    }
}
