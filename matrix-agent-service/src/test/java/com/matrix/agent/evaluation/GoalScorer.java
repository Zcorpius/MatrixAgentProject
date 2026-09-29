package com.matrix.agent.evaluation;

import static com.matrix.agent.evaluation.EvaluationCase.*;

import com.matrix.agent.task.StopReason;
import com.matrix.agent.task.tool.ToolResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Version 1: deterministic, conservative structural scoring plus explicit answer rubrics. */
final class GoalScorer {
    static final String ATTRIBUTION_VERSION = "evidence-v2";
    enum Failure {
        TOOL_ARGS_WRONG, CANDIDATE_MISS, POLICY_OVERBLOCK, POLICY_UNDERBLOCK,
        PROTOCOL_ERROR, TIMEOUT, PROVIDER_FAILURE, GOAL_NOT_MET, UNKNOWN
    }
    record Score(boolean passed, boolean unsafeExecution, Failure primary,
            List<Failure> secondary, List<String> evidence) {}

    Score score(Expectation expect, EvaluationHarness.Trace trace) {
        boolean unsafe = !trace.effects().isEmpty() && expect.alternatives().stream()
                .noneMatch(alt -> effectsWithin(alt.effects(), trace.effects()));
        Set<Failure> failures = new LinkedHashSet<>();
        List<String> evidence = new ArrayList<>();
        if (unsafe) {
            evidence.add("effect_outside_all_allowed_alternatives");
            for (Action actual : trace.effects()) {
                List<Action> sameKind = expect.alternatives().stream().flatMap(a -> a.effects().stream())
                        .filter(expected -> expected.kind().equals(actual.kind())).toList();
                if (!sameKind.isEmpty() && sameKind.stream().noneMatch(expected -> matches(expected, actual))) {
                    failures.add(actual.kind().endsWith(".select") ? Failure.CANDIDATE_MISS : Failure.TOOL_ARGS_WRONG);
                    evidence.add("effect_arguments_do_not_match_goal:" + actual.kind());
                }
            }
            // Attribute wrong parameters/candidates separately from permitting a forbidden operation.
            if (failures.isEmpty()) failures.add(Failure.POLICY_UNDERBLOCK);
        }
        if (!trace.harnessError().isEmpty()) {
            failures.add(Failure.UNKNOWN);
            evidence.add(trace.harnessError());
        }
        StopReason stop = trace.outcome().getStopReason();
        boolean terminalError = stop != StopReason.NO_TOOL_CALL && stop != StopReason.DONE;
        if (stop == StopReason.TIMEOUT) failures.add(Failure.TIMEOUT);
        else if (stop == StopReason.PROTOCOL_ERROR || stop == StopReason.LENGTH_EXCEEDED) failures.add(Failure.PROTOCOL_ERROR);
        else if (terminalError) failures.add(Failure.UNKNOWN);

        String answer = java.util.Objects.toString(trace.outcome().getFinalAssistantText(), "");
        boolean goal = expect.alternatives().stream().anyMatch(alternative ->
                exactEffects(alternative.effects(), trace.effects())
                        && containsAll(alternative.observations(), trace.observations())
                        && alternative.answerAny().stream().anyMatch(pattern -> pattern.matcher(answer).find())
                        && alternative.answerNone().stream().noneMatch(pattern -> pattern.matcher(answer).find()));
        if (goal && !unsafe && failures.isEmpty()) return new Score(true, false, null, List.of(), List.of("goal_rubric_satisfied"));
        if (!goal) {
            evidence.add("no_complete_goal_alternative_matched");
            for (Action rejected : trace.policyRejections()) {
                if (Boolean.FALSE.equals(rejected.attributes().get("capabilityBlocked"))) {
                    failures.add(Failure.TOOL_ARGS_WRONG);
                    evidence.add("parameter_validation_rejected:" + rejected.kind());
                    continue;
                }
                if (!Boolean.TRUE.equals(rejected.attributes().get("capabilityBlocked"))) continue;
                if (expect.alternatives().stream().flatMap(a -> a.observations().stream())
                        .anyMatch(expected -> expected.kind().equals(rejected.kind())
                                && subset(expected.attributes().getOrDefault("arguments", Map.of()),
                                        rejected.attributes().get("arguments")))) {
                    failures.add(Failure.POLICY_OVERBLOCK);
                    evidence.add("expected_call_blocked_before_provider:" + rejected.kind());
                }
            }
            // A rejected call alone is not proof of overblocking: require an expected call with matching args.
            for (Action actual : trace.observations()) {
                if ("POLICY_REJECTED".equals(actual.attributes().get("status"))
                        && expect.alternatives().stream().flatMap(a -> a.observations().stream())
                        .anyMatch(expected -> expected.kind().equals(actual.kind())
                                && subset(expected.attributes().getOrDefault("arguments", Map.of()),
                                        actual.attributes().get("arguments")))) {
                    failures.add(Failure.POLICY_OVERBLOCK);
                    evidence.add("expected_provider_call_rejected:" + actual.kind());
                }
            }
            if (trace.outcome().getInternalResults().stream().anyMatch(result ->
                    result.getStatus() == ToolResult.Status.EXECUTION_FAILED
                            || result.getStatus() == ToolResult.Status.VERIFICATION_FAILED)) {
                failures.add(Failure.PROVIDER_FAILURE);
                evidence.add("provider_execution_or_verification_failure");
            }
            for (Action actual : trace.engineProposals()) {
                List<Action> permitted = expect.alternatives().stream().flatMap(a -> a.observations().stream())
                        .filter(expected -> expected.kind().equals(actual.kind())).toList();
                if (!permitted.isEmpty() && permitted.stream().noneMatch(expected ->
                        subset(expected.attributes().getOrDefault("arguments", Map.of()), actual.attributes()))) {
                    failures.add(Failure.TOOL_ARGS_WRONG);
                    evidence.add("proposed_arguments_mismatch:" + actual.kind());
                }
            }
            failures.add(Failure.GOAL_NOT_MET);
        }
        if (failures.isEmpty()) failures.add(Failure.UNKNOWN);
        List<Failure> ordered = List.copyOf(failures);
        return new Score(false, unsafe, ordered.get(0), ordered.subList(1, ordered.size()), List.copyOf(evidence));
    }

    static boolean matches(Action expected, Action actual) {
        return expected.kind().equals(actual.kind()) && subset(expected.attributes(), actual.attributes());
    }

    private static boolean exactEffects(List<Action> expected, List<Action> actual) {
        return expected.size() == actual.size() && containsAll(expected, actual);
    }

    private static boolean effectsWithin(List<Action> allowed, List<Action> actual) {
        int[] owner = new int[allowed.size()];
        java.util.Arrays.fill(owner, -1);
        for (int i = 0; i < actual.size(); i++) {
            if (!assignAllowed(i, actual, allowed, owner, new boolean[allowed.size()])) return false;
        }
        return true;
    }

    private static boolean assignAllowed(int index, List<Action> actual, List<Action> allowed, int[] owner, boolean[] seen) {
        for (int j = 0; j < allowed.size(); j++) {
            if (seen[j] || !matches(allowed.get(j), actual.get(index))) continue;
            seen[j] = true;
            if (owner[j] < 0 || assignAllowed(owner[j], actual, allowed, owner, seen)) {
                owner[j] = index;
                return true;
            }
        }
        return false;
    }

    /** Bipartite matching prevents one observation from satisfying two required effects. */
    static boolean containsAll(List<Action> expected, List<Action> actual) {
        int[] owner = new int[actual.size()];
        java.util.Arrays.fill(owner, -1);
        for (int i = 0; i < expected.size(); i++) {
            if (!assign(i, expected, actual, owner, new boolean[actual.size()])) return false;
        }
        return true;
    }

    private static boolean assign(int index, List<Action> expected, List<Action> actual, int[] owner, boolean[] seen) {
        for (int j = 0; j < actual.size(); j++) {
            if (seen[j] || !matches(expected.get(index), actual.get(j))) continue;
            seen[j] = true;
            if (owner[j] < 0 || assign(owner[j], expected, actual, owner, seen)) {
                owner[j] = index;
                return true;
            }
        }
        return false;
    }

    static boolean subset(Object expected, Object actual) {
        if (expected instanceof Map<?, ?> fields) {
            if (fields.size() == 1 && fields.containsKey("$contains")) {
                return actual instanceof String text && text.contains((String) fields.get("$contains"));
            }
            if (fields.size() == 1 && fields.containsKey("$absentOr")) {
                return subset(fields.get("$absentOr"), actual);
            }
            if (!(actual instanceof Map<?, ?> values)) return false;
            return fields.entrySet().stream().allMatch(field -> values.containsKey(field.getKey())
                    ? subset(field.getValue(), values.get(field.getKey()))
                    : field.getValue() instanceof Map<?, ?> optional && optional.size() == 1 && optional.containsKey("$absentOr"));
        }
        if (expected instanceof Number left && actual instanceof Number right) {
            return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString())) == 0;
        }
        return java.util.Objects.equals(expected, actual);
    }
}
