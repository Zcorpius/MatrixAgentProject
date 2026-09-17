package com.matrix.agent.data.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

import com.matrix.agent.task.identity.VehicleZone;

import org.junit.Test;

/**
 * MemoryScope 双维度隔离键契约测试。
 *
 * <p>storageKey 格式稳定性是关键——持久化层 MemoryRecordEntity 主键依赖此格式,
 * 变更需要 Room Migration。ofLegacy 是旧版兼容入口,zone 必须为 GLOBAL。
 */
public final class MemoryScopeTest {

    @Test
    public void ofLegacySetsGlobalZone() {
        MemoryScope scope = MemoryScope.ofLegacy("demo-driver");
        assertEquals("demo-driver", scope.getUserId());
        assertEquals(VehicleZone.GLOBAL, scope.getZone());
    }

    @Test
    public void explicitZoneIsPreserved() {
        MemoryScope scope = new MemoryScope("demo-passenger", VehicleZone.PASSENGER);
        assertEquals("demo-passenger", scope.getUserId());
        assertEquals(VehicleZone.PASSENGER, scope.getZone());
    }

    @Test
    public void nullZoneFallsBackToGlobal() {
        MemoryScope scope = new MemoryScope("demo-driver", null);
        assertEquals(VehicleZone.GLOBAL, scope.getZone());
    }

    @Test
    public void emptyUserIdRejected() {
        assertThrows(IllegalArgumentException.class, () -> new MemoryScope("", VehicleZone.DRIVER));
        assertThrows(IllegalArgumentException.class, () -> new MemoryScope(null, VehicleZone.DRIVER));
    }

    @Test
    public void storageKeyFormatStable() {
        MemoryScope scope = new MemoryScope("demo-driver", VehicleZone.DRIVER);
        assertEquals("demo-driver@driver#preference/home",
                scope.storageKey(MemoryLayer.PREFERENCE, "home"));
        assertEquals("demo-driver@driver#working/turn-0",
                scope.storageKey(MemoryLayer.WORKING, "turn-0"));
    }

    @Test
    public void legacyScopeStorageKeyUsesGlobalZone() {
        MemoryScope legacy = MemoryScope.ofLegacy("demo-passenger");
        assertEquals("demo-passenger@global#preference/temp",
                legacy.storageKey(MemoryLayer.PREFERENCE, "temp"));
    }

    @Test
    public void equalsAndHashKeyOnUserIdAndZone() {
        MemoryScope a = new MemoryScope("demo-driver", VehicleZone.DRIVER);
        MemoryScope b = new MemoryScope("demo-driver", VehicleZone.DRIVER);
        MemoryScope c = new MemoryScope("demo-driver", VehicleZone.PASSENGER);
        MemoryScope d = new MemoryScope("demo-passenger", VehicleZone.DRIVER);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
        assertNotEquals(a, d);
    }
}
