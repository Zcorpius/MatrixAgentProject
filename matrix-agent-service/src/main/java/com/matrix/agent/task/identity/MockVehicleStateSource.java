package com.matrix.agent.task.identity;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 可配置的 mock {@link VehicleStateSource}。
 *
 * <p>默认 {@link VehicleState#satisfyAllPredicates()}(满足所有约束,capability 全放行)。
 * 提供 setter 供测试和 demo 切换状态(如 {@code setGear(Gear.D)} 触发 PARKED_ONLY 拒绝)。
 *
 * <p>线程安全:state 用 {@link AtomicReference} 持有,setter / snapshot 全无锁。
 */
public final class MockVehicleStateSource implements VehicleStateSource {
    private final AtomicReference<VehicleState> current;

    public MockVehicleStateSource() {
        this(VehicleState.satisfyAllPredicates());
    }

    public MockVehicleStateSource(VehicleState initial) {
        if (initial == null) throw new IllegalArgumentException("initial state 不能为空");
        this.current = new AtomicReference<>(initial);
    }

    @Override
    public VehicleState snapshot() {
        return current.get();
    }

    public MockVehicleStateSource setGear(VehicleState.Gear gear) {
        if (gear == null) throw new IllegalArgumentException("gear 不能为空");
        update(b -> b.gear(gear));
        return this;
    }

    public MockVehicleStateSource setSpeedKmh(double speedKmh) {
        update(b -> b.speedKmh(speedKmh));
        return this;
    }

    public MockVehicleStateSource setEngineRunning(boolean engineRunning) {
        update(b -> b.engineRunning(engineRunning));
        return this;
    }

    public MockVehicleStateSource setCharging(boolean charging) {
        update(b -> b.charging(charging));
        return this;
    }

    public MockVehicleStateSource setBatteryPercent(double percent) {
        update(b -> b.batteryPercent(percent));
        return this;
    }

    public MockVehicleStateSource set(VehicleState state) {
        if (state == null) throw new IllegalArgumentException("state 不能为空");
        current.set(state);
        return this;
    }

    private void update(java.util.function.Consumer<VehicleState.Builder> mutator) {
        VehicleState old;
        VehicleState next;
        do {
            old = current.get();
            VehicleState.Builder b = VehicleState.builder()
                    .gear(old.getGear())
                    .speedKmh(old.getSpeedKmh())
                    .engineRunning(old.isEngineRunning())
                    .charging(old.isCharging())
                    .batteryPercent(old.getBatteryPercent());
            mutator.accept(b);
            next = b.build();
        } while (!current.compareAndSet(old, next));
    }
}
