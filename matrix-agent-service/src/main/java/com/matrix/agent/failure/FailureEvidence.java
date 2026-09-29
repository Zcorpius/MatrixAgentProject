package com.matrix.agent.failure;

import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.task.StopReason;
import com.matrix.agent.task.TaskState;

import java.util.ArrayList;
import java.util.List;

/** An intentionally lossy projection: no request, arguments, messages or observed values. */
public record FailureEvidence(TaskState state, StopReason stopReason, List<Item> items) {
    public enum Signal { CAPABILITY_REJECTED, PARAMETER_REJECTED, VERIFICATION_FAILED,
        EXECUTION_UNKNOWN, EXECUTION_FAILED, TIMED_OUT, CANCELLED, SUCCESS, UNKNOWN }

    public record Item(int ref, String capability, Signal signal, boolean verified) {
        public Item {
            if (ref < 0 || ref >= 32 || capability == null || capability.length() > 64
                    || !capability.matches("[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+")) {
                throw new IllegalArgumentException("invalid evidence identity");
            }
            java.util.Objects.requireNonNull(signal);
        }
    }

    public FailureEvidence {
        if (state != TaskState.FAILED && state != TaskState.PARTIALLY_SUCCEEDED) {
            throw new IllegalArgumentException("ineligible terminal");
        }
        java.util.Objects.requireNonNull(stopReason);
        items = List.copyOf(items);
        if (items.size() > 32) throw new IllegalArgumentException("too much evidence");
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).ref() != i) throw new IllegalArgumentException("noncanonical reference");
        }
    }

    public static FailureEvidence project(AgentOutcome outcome) {
        List<Item> items = new ArrayList<>();
        for (var iteration : outcome.getTrajectory().getIterations()) {
            for (var observation : iteration.getObservations()) {
                if (items.size() == 32) break;
                try {
                    Signal signal = observation.getRejectionReason() != null
                            ? (observation.isCapabilityBlocked() ? Signal.CAPABILITY_REJECTED
                                : Signal.PARAMETER_REJECTED)
                            : observation.getResult() == null ? Signal.UNKNOWN
                                : Signal.valueOf(observation.getResult().getStatus().name()
                                    .replace("POLICY_REJECTED", "CAPABILITY_REJECTED"));
                    items.add(new Item(items.size(), observation.getCapabilityName(), signal,
                            observation.getResult() != null && observation.getResult().isVerified()));
                } catch (IllegalArgumentException invalidEvidence) {
                    // Unknown status or capability names are not promoted into model input.
                }
            }
        }
        return new FailureEvidence(outcome.getFinalState(), outcome.getStopReason(), items);
    }
}
