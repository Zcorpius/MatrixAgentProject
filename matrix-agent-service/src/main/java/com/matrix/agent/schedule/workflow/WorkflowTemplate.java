package com.matrix.agent.schedule.workflow;

import java.util.*;

/** Versioned, Host-authored graph. Neither model output nor calendar text can add edges or capabilities. */
public record WorkflowTemplate(String id, int version, String title, String description, List<Step> steps) {
    public enum Kind { TOOL, AGENT, TRANSFORM, DELIVER }
    public record Step(String id, String title, Kind kind, List<String> dependencies, boolean required,
            boolean readOnly, String capability, long timeoutMillis, int maxRetries, String resourceKey) {
        public Step { dependencies = List.copyOf(dependencies); }
    }
    public WorkflowTemplate {
        steps = List.copyOf(steps);
        if (id == null || version < 1 || steps.isEmpty() || steps.size() > 8) throw new IllegalArgumentException("invalid template");
        Map<String, Step> nodes = new LinkedHashMap<>();
        for (Step step : steps) {
            if (!step.id().matches("[a-z][a-z0-9_-]{0,31}") || nodes.put(step.id(), step) != null
                    || step.timeoutMillis() < 1 || step.timeoutMillis() > 60_000 || step.maxRetries() < 0 || step.maxRetries() > 2
                    || (!step.readOnly() && step.maxRetries() != 0)) throw new IllegalArgumentException("invalid step");
        }
        Set<String> done = new HashSet<>();
        for (int i = 0; i < steps.size(); i++) for (Step step : steps) {
            if (done.containsAll(step.dependencies())) done.add(step.id());
        }
        if (done.size() != steps.size()) throw new IllegalArgumentException("cyclic graph or missing dependency");
        if (steps.stream().filter(step -> step.kind() == Kind.DELIVER).count() != 1) throw new IllegalArgumentException("one delivery step required");
    }
    public Set<String> capabilities() {
        Set<String> result = new LinkedHashSet<>(); for (Step step : steps) if (!step.capability().isEmpty()) result.add(step.capability()); return Set.copyOf(result);
    }
}
