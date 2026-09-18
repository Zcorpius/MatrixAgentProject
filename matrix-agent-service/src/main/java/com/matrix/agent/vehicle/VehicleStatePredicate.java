package com.matrix.agent.vehicle;

/**
 * 车辆状态谓词——简化枚举,不引 DSL。
 *
 * <p>每个 predicate 在 {@link #matches(VehicleState)} 中实现具体判定。
 * Capability 通过 {@code requiredVehicleStates} 声明 0..N 个 predicate,
 * PolicyEngine 在 schema 校验之前用 AND 语义判定(全部 predicate 都满足才放行)。
 *
 * <p>需要 AND/OR/NOT 组合时显式新增 enum 值,DSL 留后续版本。
 */
public enum VehicleStatePredicate {
    /** gear == P 且 speedKmh &lt; 2(完全停稳且挂 P 档)。 */
    PARKED_ONLY,
    /** speedKmh &lt; 2(车辆几乎不动,无论档位)。 */
    STOPPED_OR_PARKED,
    /** engineRunning == true(动力系统已就绪)。 */
    ENGINE_RUNNING,
    /** charging == false(未在充电)。 */
    NOT_CHARGING;

    public boolean matches(VehicleState state) {
        if (state == null) return false;
        switch (this) {
            case PARKED_ONLY:
                return state.getGear() == VehicleState.Gear.P && state.getSpeedKmh() < 2;
            case STOPPED_OR_PARKED:
                return state.getSpeedKmh() < 2;
            case ENGINE_RUNNING:
                return state.isEngineRunning();
            case NOT_CHARGING:
                return !state.isCharging();
            default:
                return false;
        }
    }
}
