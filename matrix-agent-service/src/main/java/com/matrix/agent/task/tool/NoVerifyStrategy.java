package com.matrix.agent.task.tool;

/**
 * 不 verify 策略——{@link com.matrix.agent.task.capability.VerifyMethod#NONE} 对应实现。
 *
 * <p>对应 capability:vehicle.info.get_battery / vehicle.info.get_tire_pressure / knowledge.answer。
 * 这些 capability 是只读或纯回执,无 commandAndApply,verify 永远 true。
 */
public final class NoVerifyStrategy implements VerifyStrategy {
    public static final NoVerifyStrategy INSTANCE = new NoVerifyStrategy();

    private NoVerifyStrategy() {}

    @Override
    public boolean verify(ProviderContext ctx) {
        return true;
    }
}
