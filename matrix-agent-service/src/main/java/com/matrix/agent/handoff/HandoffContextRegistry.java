package com.matrix.agent.handoff;

import java.util.concurrent.ConcurrentHashMap;

/** Only persisted PRIMARY rounds enter this registry; steer retains its host round. */
public final class HandoffContextRegistry {
    public record Binding(String runtimeRequestId, String conversationId, String conversationTaskId,
            String hostUserMessageId, long hostUserSequence, String ownerUserId, String zone) {}
    private final ConcurrentHashMap<String, Binding> bindings = new ConcurrentHashMap<>();
    public void bind(Binding binding) { bindings.put(binding.runtimeRequestId(), binding); }
    public Binding find(String runtimeRequestId) { return bindings.get(runtimeRequestId); }
    public void unbind(String runtimeRequestId) { bindings.remove(runtimeRequestId); }
    public void clear() { bindings.clear(); }
}
