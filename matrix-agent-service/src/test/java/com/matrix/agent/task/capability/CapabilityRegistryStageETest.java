package com.matrix.agent.task.capability;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.task.identity.VehicleZone;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import org.junit.Test;

/**
 * CapabilityRegistry zone 过滤 + readOnlyHint 派生测试。
 */
public final class CapabilityRegistryStageETest {

    /** 默认 toToolDefinitions() 不过滤——10 个 capability 都暴露(新增 memory.semantic.* 2 个)。 */
    @Test
    public void toToolDefinitionsNoZoneReturnsAll() {
        List<ToolDefinition> tools = CapabilityRegistry.createDemoRegistry().toToolDefinitions();
        assertEquals(10, tools.size());
    }

    /** toToolDefinitions(null) 等价于默认——不过滤。 */
    @Test
    public void toToolDefinitionsWithNullZoneReturnsAll() {
        List<ToolDefinition> tools = CapabilityRegistry.createDemoRegistry()
                .toToolDefinitions(null);
        assertEquals(10, tools.size());
    }

    /** toToolDefinitions(DRIVER) 含 driver-allowed capability(climate 写 + 查询等)。 */
    @Test
    public void toToolDefinitionsDriverIncludesClimate() {
        CapabilityRegistry registry = CapabilityRegistry.createDemoRegistry();
        List<ToolDefinition> tools = registry.toToolDefinitions(VehicleZone.DRIVER);
        // climate / seat 写操作 allowedTargetZones={DRIVER, PASSENGER},都应暴露
        boolean hasClimate = false;
        for (ToolDefinition t : tools) {
            if ("vehicle_climate_set_temperature".equals(t.getModelName())) {
                hasClimate = true;
                break;
            }
        }
        assertTrue("DRIVER zone 应包含 climate capability", hasClimate);
    }

    /** toToolDefinitions(PASSENGER) 也含 climate——allowedTargetZones 双 zone。 */
    @Test
    public void toToolDefinitionsPassengerIncludesClimate() {
        CapabilityRegistry registry = CapabilityRegistry.createDemoRegistry();
        List<ToolDefinition> tools = registry.toToolDefinitions(VehicleZone.PASSENGER);
        boolean hasClimate = false;
        for (ToolDefinition t : tools) {
            if ("vehicle_climate_set_temperature".equals(t.getModelName())) {
                hasClimate = true;
                break;
            }
        }
        assertTrue("PASSENGER zone 应包含 climate capability", hasClimate);
    }

    /** R3_PROHIBITED 始终过滤——vehicle.brake.apply 不在任何 zone 出现。 */
    @Test
    public void r3ProhibitedAlwaysFiltered() {
        CapabilityRegistry registry = CapabilityRegistry.createDemoRegistry();
        for (ToolDefinition t : registry.toToolDefinitions()) {
            assertFalse("R3 不应进 tool definitions: " + t.getModelName(),
                    t.getModelName().contains("brake"));
        }
    }

    /** deriveReadOnlyHint:集合内所有 capability 都不是 writeOperation → true。 */
    @Test
    public void deriveReadOnlyHintReturnsTrueForReadOnlyCapabilities() {
        CapabilityRegistry registry = CapabilityRegistry.createDemoRegistry();
        assertTrue(registry.deriveReadOnlyHint(Arrays.asList(
                "vehicle.info.get_battery",
                "knowledge.answer")));
    }

    /** deriveReadOnlyHint:含 writeOperation → false。 */
    @Test
    public void deriveReadOnlyHintReturnsFalseForWriteCapability() {
        CapabilityRegistry registry = CapabilityRegistry.createDemoRegistry();
        assertFalse(registry.deriveReadOnlyHint(Arrays.asList(
                "vehicle.info.get_battery",
                "vehicle.climate.set_temperature")));
    }

    /** deriveReadOnlyHint:空 / null → 保守 false。 */
    @Test
    public void deriveReadOnlyHintReturnsFalseForEmptyOrNull() {
        CapabilityRegistry registry = CapabilityRegistry.createDemoRegistry();
        assertFalse(registry.deriveReadOnlyHint((java.util.Collection<String>) null));
        assertFalse(registry.deriveReadOnlyHint(Collections.emptyList()));
    }

    /** deriveReadOnlyHint(VehicleZone) overload——demo registry 任一 zone 都含写 → false。 */
    @Test
    public void deriveReadOnlyHintByZoneReturnsFalseForWriteableZone() {
        CapabilityRegistry registry = CapabilityRegistry.createDemoRegistry();
        assertFalse("DRIVER zone 含 climate 写操作 → false",
                registry.deriveReadOnlyHint(VehicleZone.DRIVER));
        assertFalse("PASSENGER zone 也含 climate 写操作 → false",
                registry.deriveReadOnlyHint(VehicleZone.PASSENGER));
        assertFalse("null zone 保守返回 false",
                registry.deriveReadOnlyHint((VehicleZone) null));
    }

    /** deriveReadOnlyHint:未知 capability 不影响 hint(由其他验证层处理)。 */
    @Test
    public void deriveReadOnlyHintIgnoresUnknownCapability() {
        CapabilityRegistry registry = CapabilityRegistry.createDemoRegistry();
        assertTrue("未知 capability 不应让 hint 翻为 false",
                registry.deriveReadOnlyHint(Arrays.asList(
                        "vehicle.info.get_battery",
                        "unknown.capability")));
    }
}
