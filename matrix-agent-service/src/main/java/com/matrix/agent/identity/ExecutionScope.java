package com.matrix.agent.identity;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Host-issued automatic execution authority, carried unchanged to the last model/tool boundary. */
public final class ExecutionScope {
    public interface Guard {
        /** Empty means still authorized. Implementations check epoch, cancellation and current user. */
        String rejection();
        long remainingMillis();
        boolean reserveTool();
        default boolean reserveModelCall() { return true; }
        /** Runs on the caller before occupying the provider pool; may perform controlled source verification. */
        default boolean prepareTool() { return true; }
    }
    public static final ExecutionScope INTERACTIVE = new ExecutionScope(false, Set.of(), true, null, ExecutionProfile.INTERACTIVE);
    private final boolean restricted;
    private final Set<String> capabilities;
    private final boolean network;
    private final Guard guard;
    private final ExecutionProfile profile;
    private final AtomicInteger toolCalls = new AtomicInteger();
    private ExecutionScope(boolean restricted, Set<String> capabilities, boolean network, Guard guard, ExecutionProfile profile) {
        this.profile = java.util.Objects.requireNonNull(profile);
        this.restricted = restricted; this.capabilities = Set.copyOf(capabilities); this.network = network; this.guard = guard;
    }
    public static ExecutionScope automatic(Set<String> capabilities, boolean network, Guard guard) {
        return automatic(capabilities, network, guard, ExecutionProfile.INTERACTIVE);
    }
    public static ExecutionScope automatic(Set<String> capabilities, boolean network, Guard guard, ExecutionProfile profile) {
        if (guard == null) throw new IllegalArgumentException("automatic scope requires a live authority guard");
        return new ExecutionScope(true, capabilities, network, guard, profile);
    }
    public ExecutionProfile profile() { return profile; }
    public boolean automatic() { return restricted; }
    public boolean allows(String capability) { return !restricted || capabilities.contains(capability); }
    public boolean networkAllowed() { return network; }
    public String rejection() { return guard == null ? "" : guard.rejection(); }
    public long remainingMillis() { return guard == null ? Long.MAX_VALUE : Math.max(0, guard.remainingMillis()); }
    public boolean prepareTool() { return guard == null || guard.prepareTool(); }
    public boolean reserveTool() {
        if (!restricted) return true;
        if (!rejection().isEmpty() || remainingMillis() == 0) return false;
        while (true) {
            int current = toolCalls.get();
            if (current >= profile.maxToolCalls()) return false;
            if (toolCalls.compareAndSet(current, current + 1)) break;
        }
        return guard.reserveTool();
    }
    public boolean reserveModelCall() {
        return !restricted || (rejection().isEmpty() && remainingMillis() > 0 && guard.reserveModelCall());
    }
    public int toolCalls() { return toolCalls.get(); }
}
