package com.matrix.agent.task.identity;

import static org.junit.Assert.assertFalse;

import org.junit.Test;

/** Production fallback must never turn missing vehicle telemetry into permission to write. */
public final class DefaultVehicleStateSourceTest {
    @Test
    public void unavailableStateFailsEveryPhysicalSafetyPredicate() {
        VehicleState state = new DefaultVehicleStateSource().snapshot();
        for (VehicleStatePredicate predicate : VehicleStatePredicate.values()) {
            assertFalse("missing telemetry must fail closed for " + predicate, predicate.matches(state));
        }
    }
}
