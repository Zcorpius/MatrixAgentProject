package com.matrix.agent.vehicle;

/**
 * Production-safe fallback {@link VehicleStateSource}.
 *
 * <p>不引入 {@code android.car.*} 依赖。没有 OEM {@code CarPropertyManager} 适配器时，
 * 本类返回 {@link VehicleState#unavailable()}，让所有带物理前置条件的写能力 fail-closed。
 * 测试和 demo 必须显式注入 {@link MockVehicleStateSource}，不能由生产默认值隐式放行。
 *
 * <p>后续版本计划:
 * <ul>
 *   <li>接入 {@code android.car.hardware.CarPropertyManager}</li>
 *   <li>实时读取 gear / speed / charging / batteryPercent</li>
 *   <li>读失败时继续返回安全 unavailable state，拒绝物理写操作</li>
 * </ul>
 */
public final class DefaultVehicleStateSource implements VehicleStateSource {
    @Override
    public VehicleState snapshot() {
        return VehicleState.unavailable();
    }
}
