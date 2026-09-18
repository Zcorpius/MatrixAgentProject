package com.matrix.agent.task.tool;

import com.matrix.agent.contract.ToolCall;
import java.util.function.Function;

/**
 * 回读字段 verify 策略——{@link com.matrix.agent.task.capability.VerifyMethod#READBACK_FIELD}
 * 对应实现。
 *
 * <p>对应 capability:vehicle.climate.set_temperature / vehicle.seat.set_heating_level /
 * navigation.start_route。语义:命令下发后从 observedState 读 key 字段,与 call 中的 expected 比对。
 *
 * <p>stateKey 和 expected 都是 call-derived(climate 的 key 是 zone + ".temperature",
 * navigation 的 key 是固定 "navigation.destination")——通过 Function 注入。
 */
public final class ReadbackFieldStrategy implements VerifyStrategy {
    private final Function<ToolCall, String> keyExtractor;
    private final Function<ToolCall, Object> expectedExtractor;

    public ReadbackFieldStrategy(Function<ToolCall, String> keyExtractor,
            Function<ToolCall, Object> expectedExtractor) {
        if (keyExtractor == null) throw new IllegalArgumentException("keyExtractor 不能为空");
        if (expectedExtractor == null) throw new IllegalArgumentException("expectedExtractor 不能为空");
        this.keyExtractor = keyExtractor;
        this.expectedExtractor = expectedExtractor;
    }

    @Override
    public boolean verify(ProviderContext ctx) {
        String key = keyExtractor.apply(ctx.getCall());
        Object expected = expectedExtractor.apply(ctx.getCall());
        Object actual = ctx.getObservedState().get(key);
        return expected == null ? actual == null : expected.equals(actual);
    }
}
