package com.matrix.agent.task.identity;

/**
 * 不可变车辆状态 value class。
 *
 * <p>{@link AgentRequest#getCurrentVehicleState()} 携带当前车辆状态,
 * {@link com.matrix.agent.task.policy.PolicyEngine} 在 schema 校验之前用它判定
 * {@code requiredVehicleStates} 前置条件(归 CAPABILITY 拒绝,不可上诉)。
 *
 * <p>mock 数据——MockCapabilityProvider.snapshotVehicleState
 * 直接构造同款 state 注入 AgentRequest。后续版本接真实 Car API 后由
 * VehiclePropertyService 实时读取。
 *
 * <p>默认 mock state {@link #satisfyAllPredicates()}:{@code gear=P, speedKmh=0,
 * engineRunning=true, charging=false, batteryPercent=80}——满足所有
 * {@link VehicleStatePredicate},确保现有测试不退绿。
 */
public final class VehicleState {
    public enum Gear { P, R, N, D }

    private final Gear gear;
    private final double speedKmh;
    private final boolean engineRunning;
    private final boolean charging;
    private final double batteryPercent;

    private VehicleState(Builder builder) {
        gear = builder.gear;
        speedKmh = builder.speedKmh;
        engineRunning = builder.engineRunning;
        charging = builder.charging;
        batteryPercent = builder.batteryPercent;
    }

    public Gear getGear() { return gear; }
    public double getSpeedKmh() { return speedKmh; }
    public boolean isEngineRunning() { return engineRunning; }
    public boolean isCharging() { return charging; }
    public double getBatteryPercent() { return batteryPercent; }

    /** 默认 mock state——满足所有 {@link VehicleStatePredicate},用作测试默认值。 */
    public static VehicleState satisfyAllPredicates() {
        return new Builder()
                .gear(Gear.P)
                .speedKmh(0)
                .engineRunning(true)
                .charging(false)
                .batteryPercent(80)
                .build();
    }

    /**
     * Conservative state used when no trusted vehicle-property provider is present.
     *
     * <p>Every currently defined safety predicate evaluates false. This intentionally permits
     * read-only capabilities while preventing a host from treating an unknown physical vehicle
     * as parked, stationary, powered, or unplugged.
     */
    public static VehicleState unavailable() {
        return new Builder()
                .gear(Gear.D)
                .speedKmh(2)
                .engineRunning(false)
                .charging(true)
                .batteryPercent(0)
                .build();
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Gear gear = Gear.P;
        private double speedKmh;
        private boolean engineRunning = true;
        private boolean charging;
        private double batteryPercent = 80;

        private Builder() {}

        public Builder gear(Gear value) { gear = value; return this; }
        public Builder speedKmh(double value) { speedKmh = value; return this; }
        public Builder engineRunning(boolean value) { engineRunning = value; return this; }
        public Builder charging(boolean value) { charging = value; return this; }
        public Builder batteryPercent(double value) { batteryPercent = value; return this; }

        public VehicleState build() {
            if (gear == null) throw new IllegalArgumentException("gear 不能为空");
            if (!Double.isFinite(speedKmh) || speedKmh < 0) {
                throw new IllegalArgumentException("speedKmh 必须 ≥ 0");
            }
            if (!Double.isFinite(batteryPercent) || batteryPercent < 0 || batteryPercent > 100) {
                throw new IllegalArgumentException("batteryPercent 必须在 0 到 100 之间");
            }
            return new VehicleState(this);
        }
    }
}
