package com.matrix.agent.task.identity;

import com.matrix.agent.vehicle.VehicleStatePredicate;

import com.matrix.agent.vehicle.VehicleState;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * VehicleStatePredicate 单元测试。
 *
 * <p>每个 predicate 覆盖满足 / 不满足两种情况,确保 PolicyEngine 调用前行为已校准。
 */
public final class VehicleStatePredicateTest {

    @Test
    public void parkedOnlyMatchesWhenGearPAndSpeedLow() {
        VehicleState parked = VehicleState.builder()
                .gear(VehicleState.Gear.P).speedKmh(0).build();
        assertTrue(VehicleStatePredicate.PARKED_ONLY.matches(parked));

        VehicleState crawling = VehicleState.builder()
                .gear(VehicleState.Gear.P).speedKmh(1.5).build();
        assertTrue("speedKmh<2 仍视为停驶", VehicleStatePredicate.PARKED_ONLY.matches(crawling));
    }

    @Test
    public void parkedOnlyRejectsWhenGearNotP() {
        VehicleState d = VehicleState.builder()
                .gear(VehicleState.Gear.D).speedKmh(0).build();
        assertFalse(VehicleStatePredicate.PARKED_ONLY.matches(d));
    }

    @Test
    public void parkedOnlyRejectsWhenSpeedHigh() {
        VehicleState moving = VehicleState.builder()
                .gear(VehicleState.Gear.P).speedKmh(5).build();
        assertFalse(VehicleStatePredicate.PARKED_ONLY.matches(moving));
    }

    @Test
    public void stoppedOrParkedMatchesRegardlessOfGear() {
        VehicleState d = VehicleState.builder()
                .gear(VehicleState.Gear.D).speedKmh(0).build();
        assertTrue(VehicleStatePredicate.STOPPED_OR_PARKED.matches(d));

        VehicleState n = VehicleState.builder()
                .gear(VehicleState.Gear.N).speedKmh(1.9).build();
        assertTrue(VehicleStatePredicate.STOPPED_OR_PARKED.matches(n));
    }

    @Test
    public void engineRunningMatchesWhenTrue() {
        VehicleState on = VehicleState.builder().engineRunning(true).build();
        assertTrue(VehicleStatePredicate.ENGINE_RUNNING.matches(on));

        VehicleState off = VehicleState.builder().engineRunning(false).build();
        assertFalse(VehicleStatePredicate.ENGINE_RUNNING.matches(off));
    }

    @Test
    public void notChargingMatchesWhenFalse() {
        VehicleState unplugged = VehicleState.builder().charging(false).build();
        assertTrue(VehicleStatePredicate.NOT_CHARGING.matches(unplugged));

        VehicleState plugged = VehicleState.builder().charging(true).build();
        assertFalse(VehicleStatePredicate.NOT_CHARGING.matches(plugged));
    }

    @Test
    public void satisfyAllPredicatesDefaultMatchesAll() {
        VehicleState mock = VehicleState.satisfyAllPredicates();
        for (VehicleStatePredicate p : VehicleStatePredicate.values()) {
            assertTrue("mock state 应满足 " + p, p.matches(mock));
        }
    }
}
