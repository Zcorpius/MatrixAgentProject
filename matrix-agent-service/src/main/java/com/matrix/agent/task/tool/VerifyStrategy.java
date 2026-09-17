package com.matrix.agent.task.tool;

/**
 * capability verify 策略抽象。
 *
 * <p>对应 {@link com.matrix.agent.task.capability.VerifyMethod} 枚举的具体行为:
 * <ul>
 *   <li>{@code NONE} → {@link NoVerifyStrategy}(info.get_*, knowledge.answer)</li>
 *   <li>{@code READBACK_FIELD} → {@link ReadbackFieldStrategy}(climate/seat/navigation)</li>
 *   <li>{@code READBACK_GET} → {@link ReadbackGetStrategy}(memory.preference.save/get)</li>
 * </ul>
 *
 * <p>{@code USER_CONFIRM} / {@code TIMEOUT} 留后续版本实现,调用方不注册这两个策略。
 */
@FunctionalInterface
public interface VerifyStrategy {
    /**
     * 判定本次 Tool 执行是否通过 verify。
     *
     * <p>调用时机:handler 已完成 commandAndApply,observedState 已更新(或被
     * failNextVehicleReadback 跳过)。strategy 从 ctx 读 observedState / memoryStore
     * 比对 expected,返回 true/false。
     */
    boolean verify(ProviderContext ctx);
}
