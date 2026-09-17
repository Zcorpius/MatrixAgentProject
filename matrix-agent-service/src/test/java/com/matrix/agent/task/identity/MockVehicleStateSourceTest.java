package com.matrix.agent.task.identity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * MockVehicleStateSource setter / snapshot 单元测试。
 */
public final class MockVehicleStateSourceTest {

    @Test
    public void defaultSnapshotSatisfiesAllPredicates() {
        VehicleState state = new MockVehicleStateSource().snapshot();
        assertEquals(VehicleState.Gear.P, state.getGear());
        assertEquals(0d, state.getSpeedKmh(), 0.001d);
        assertTrue(state.isEngineRunning());
        assertFalse(state.isCharging());
        assertTrue(VehicleStatePredicate.PARKED_ONLY.matches(state));
        assertTrue(VehicleStatePredicate.STOPPED_OR_PARKED.matches(state));
        assertTrue(VehicleStatePredicate.ENGINE_RUNNING.matches(state));
        assertTrue(VehicleStatePredicate.NOT_CHARGING.matches(state));
    }

    @Test
    public void setGearTriggersParkedOnlyMismatch() {
        MockVehicleStateSource source = new MockVehicleStateSource();
        source.setGear(VehicleState.Gear.D);
        VehicleState state = source.snapshot();
        assertEquals(VehicleState.Gear.D, state.getGear());
        assertFalse("gear=D 不应满足 PARKED_ONLY",
                VehicleStatePredicate.PARKED_ONLY.matches(state));
        assertTrue("gear=D 仍满足 ENGINE_RUNNING(默认)",
                VehicleStatePredicate.ENGINE_RUNNING.matches(state));
    }

    @Test
    public void setSpeedKmhTriggersStoppedOrParkedMismatch() {
        MockVehicleStateSource source = new MockVehicleStateSource();
        source.setSpeedKmh(30d);
        VehicleState state = source.snapshot();
        assertFalse("speedKmh=30 不应满足 STOPPED_OR_PARKED",
                VehicleStatePredicate.STOPPED_OR_PARKED.matches(state));
        assertFalse("speedKmh=30 不应满足 PARKED_ONLY",
                VehicleStatePredicate.PARKED_ONLY.matches(state));
    }

    @Test
    public void setChargingTriggersNotChargingMismatch() {
        MockVehicleStateSource source = new MockVehicleStateSource();
        source.setCharging(true);
        assertFalse("charging=true 不应满足 NOT_CHARGING",
                VehicleStatePredicate.NOT_CHARGING.matches(source.snapshot()));
    }

    @Test
    public void setEngineRunningTriggersEngineRunningMismatch() {
        MockVehicleStateSource source = new MockVehicleStateSource();
        source.setEngineRunning(false);
        assertFalse("engineRunning=false 不应满足 ENGINE_RUNNING",
                VehicleStatePredicate.ENGINE_RUNNING.matches(source.snapshot()));
    }

    @Test
    public void setReplacesEntireState() {
        MockVehicleStateSource source = new MockVehicleStateSource();
        VehicleState custom = VehicleState.builder()
                .gear(VehicleState.Gear.N)
                .speedKmh(5d)
                .engineRunning(false)
                .charging(true)
                .batteryPercent(42d)
                .build();
        source.set(custom);
        VehicleState snapshot = source.snapshot();
        assertEquals(VehicleState.Gear.N, snapshot.getGear());
        assertEquals(5d, snapshot.getSpeedKmh(), 0.001d);
        assertFalse(snapshot.isEngineRunning());
        assertTrue(snapshot.isCharging());
        assertEquals(42d, snapshot.getBatteryPercent(), 0.001d);
    }

    @Test
    public void snapshotReflectsLatestMutation() {
        MockVehicleStateSource source = new MockVehicleStateSource();
        source.setGear(VehicleState.Gear.R);
        assertEquals(VehicleState.Gear.R, source.snapshot().getGear());
        source.setGear(VehicleState.Gear.P);
        assertEquals(VehicleState.Gear.P, source.snapshot().getGear());
    }
}
