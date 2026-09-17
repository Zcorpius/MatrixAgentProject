package com.matrix.agent.task.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.task.identity.Actor;
import com.matrix.agent.task.identity.AgentRequest;
import com.matrix.agent.task.identity.VehicleZone;
import com.matrix.agent.data.memory.InMemoryMemoryStore;
import com.matrix.agent.data.memory.MemoryScope;
import com.matrix.agent.data.memory.MemoryStore;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

/**
 * 3 个 VerifyStrategy 行为单元测试。
 *
 * <p>使用 fake ProviderContext(直接构造 observedState / memoryStore)避免依赖完整 Provider。
 */
public final class VerifyStrategyTest {

    @Test
    public void noVerifyAlwaysReturnsTrue() {
        ProviderContext ctx = newContext("cap", new HashMap<>(), new HashMap<>(),
                new InMemoryMemoryStore());
        assertTrue(NoVerifyStrategy.INSTANCE.verify(ctx));
    }

    @Test
    public void readbackFieldMatchesWhenObservedEqualsExpected() {
        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("driver.temperature", 24);
        Map<String, Object> commanded = new LinkedHashMap<>();
        ToolCall call = new ToolCall("vehicle.climate.set_temperature",
                args("zone", "driver", "temperature", 24));
        ProviderContext ctx = newContextWithCall(call, observed, commanded, new InMemoryMemoryStore());

        ReadbackFieldStrategy strategy = new ReadbackFieldStrategy(
                c -> VehicleZone.parse(c.argument("zone")).wireValue() + ".temperature",
                c -> ((Number) c.argument("temperature")).intValue());

        assertTrue("observed==expected → verify true", strategy.verify(ctx));
    }

    @Test
    public void readbackFieldRejectsWhenObservedMismatch() {
        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("driver.temperature", 22);  // 与 expected=24 不一致(模拟 failNextVehicleReadback)
        Map<String, Object> commanded = new LinkedHashMap<>();
        ToolCall call = new ToolCall("vehicle.climate.set_temperature",
                args("zone", "driver", "temperature", 24));
        ProviderContext ctx = newContextWithCall(call, observed, commanded, new InMemoryMemoryStore());

        ReadbackFieldStrategy strategy = new ReadbackFieldStrategy(
                c -> VehicleZone.parse(c.argument("zone")).wireValue() + ".temperature",
                c -> ((Number) c.argument("temperature")).intValue());

        assertFalse("observed=22 ≠ expected=24 → verify false", strategy.verify(ctx));
    }

    @Test
    public void readbackGetVerifiesPreferenceExists() {
        InMemoryMemoryStore memory = new InMemoryMemoryStore();
        memory.putPreference(new MemoryScope("demo-driver", VehicleZone.DRIVER),
                "preferred_temperature", "24");
        ToolCall call = new ToolCall("memory.preference.save",
                args("key", "preferred_temperature", "value", "24"));
        ProviderContext ctx = newContextWithCall(call, new HashMap<>(), new HashMap<>(), memory);

        ReadbackGetStrategy strategy = new ReadbackGetStrategy("key");
        assertTrue("memory 已存 key → verify true", strategy.verify(ctx));
    }

    @Test
    public void readbackGetRejectsWhenPreferenceMissing() {
        InMemoryMemoryStore memory = new InMemoryMemoryStore();
        ToolCall call = new ToolCall("memory.preference.save",
                args("key", "missing_key", "value", "x"));
        ProviderContext ctx = newContextWithCall(call, new HashMap<>(), new HashMap<>(), memory);

        ReadbackGetStrategy strategy = new ReadbackGetStrategy("key");
        assertFalse("memory 无 key → verify false", strategy.verify(ctx));
    }

    @Test
    public void providerContextCommandAndAppliesWritesBothMaps() {
        Map<String, Object> observed = new LinkedHashMap<>();
        Map<String, Object> commanded = new LinkedHashMap<>();
        ToolCall call = new ToolCall("cap", new HashMap<>());
        ProviderContext ctx = newContextWithCall(call, observed, commanded, new InMemoryMemoryStore());

        ctx.commandAndApply("driver.temperature", 25);

        assertEquals(25, commanded.get("driver.temperature"));
        assertEquals(25, observed.get("driver.temperature"));
    }

    @Test
    public void providerContextFailNextVehicleReadbackSkipsObservedSync() {
        Map<String, Object> observed = new LinkedHashMap<>();
        observed.put("driver.temperature", 22);  // 旧值
        Map<String, Object> commanded = new LinkedHashMap<>();
        AtomicBoolean failFlag = new AtomicBoolean(true);
        ToolCall call = new ToolCall("cap", new HashMap<>());
        ProviderContext ctx = new ProviderContext(
                AgentRequest.builder("test", Actor.DRIVER)
                        .sessionId("s1")
                        .occupantZone(VehicleZone.DRIVER)
                        .build(),
                call, observed, commanded, new InMemoryMemoryStore(), failFlag);

        ctx.commandAndApply("driver.temperature", 25);

        assertEquals(25, commanded.get("driver.temperature"));
        assertEquals("failNextVehicleReadback 应跳过 observedState 同步",
                22, observed.get("driver.temperature"));
        assertFalse("failNextVehicleReadback 应被消费(单次有效)",
                failFlag.get());
    }

    private static Map<String, Object> args(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }

    private static ProviderContext newContext(String capName,
            Map<String, Object> observed, Map<String, Object> commanded, MemoryStore memoryStore) {
        return newContextWithCall(new ToolCall(capName, new HashMap<>()), observed, commanded, memoryStore);
    }

    private static ProviderContext newContextWithCall(ToolCall call,
            Map<String, Object> observed, Map<String, Object> commanded, MemoryStore memoryStore) {
        return new ProviderContext(
                AgentRequest.builder("test", Actor.DRIVER)
                        .sessionId("s1")
                        .occupantZone(VehicleZone.DRIVER)
                        .build(),
                call, observed, commanded, memoryStore, new AtomicBoolean(false));
    }
}
