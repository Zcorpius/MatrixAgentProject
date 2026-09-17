package com.matrix.agent.data.memory;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.task.identity.VehicleZone;

import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * LegacyPreferenceMemorySource 桥接测试。
 *
 * <p>验证 Preference 层行为与旧版 MemoryStore.getAllPreferences 一致——
 * 返回的 snippet.key 集合与原"已保存的偏好 key 列表"完全等价,LlmPlanner.savedKeysFor 兼容。
 */
public final class PreferenceMemorySourceBridgeTest {

    @Test
    public void emptyStoreReturnsEmpty() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        LegacyPreferenceMemorySource source = new LegacyPreferenceMemorySource(store);

        List<MemorySnippet> result = source.recallPreference(
                MemoryScope.ofLegacy("demo-driver"), "查电量", 5);

        assertTrue(result.isEmpty());
    }

    @Test
    public void returnsAllPreferencesUpToMaxItems() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        store.putPreference("demo-driver", "home_address", "公司");
        store.putPreference("demo-driver", "preferred_temperature", "24");
        store.putPreference("demo-driver", "favorite_music", "jazz");
        LegacyPreferenceMemorySource source = new LegacyPreferenceMemorySource(store);

        List<MemorySnippet> result = source.recallPreference(
                MemoryScope.ofLegacy("demo-driver"), "查电量", 2);

        assertEquals(2, result.size());
        assertEquals(MemoryLayer.PREFERENCE, result.get(0).getLayer());
        assertEquals("home_address", result.get(0).getKey());
        assertEquals("公司", result.get(0).getValue());
        assertEquals("preferred_temperature", result.get(1).getKey());
        assertEquals("24", result.get(1).getValue());
    }

    @Test
    public void preferenceKeySetMatchesGetAllPreferences() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        store.putPreference("demo-driver", "k1", "v1");
        store.putPreference("demo-driver", "k2", "v2");
        store.putPreference("demo-driver", "k3", "v3");
        LegacyPreferenceMemorySource source = new LegacyPreferenceMemorySource(store);

        List<MemorySnippet> result = source.recallPreference(
                MemoryScope.ofLegacy("demo-driver"), "查电量", 10);
        Map<String, String> legacy = store.getAllPreferences("demo-driver");

        assertEquals(legacy.size(), result.size());
        for (MemorySnippet snippet : result) {
            assertEquals(legacy.get(snippet.getKey()), snippet.getValue());
        }
    }

    @Test
    public void userIdIsolationPreserved() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        store.putPreference("demo-driver", "shared_key", "driver-value");
        store.putPreference("demo-passenger", "shared_key", "passenger-value");
        LegacyPreferenceMemorySource source = new LegacyPreferenceMemorySource(store);

        List<MemorySnippet> driverResult = source.recallPreference(
                MemoryScope.ofLegacy("demo-driver"), "查电量", 5);
        List<MemorySnippet> passengerResult = source.recallPreference(
                MemoryScope.ofLegacy("demo-passenger"), "查电量", 5);

        assertEquals(1, driverResult.size());
        assertEquals("driver-value", driverResult.get(0).getValue());
        assertEquals(1, passengerResult.size());
        assertEquals("passenger-value", passengerResult.get(0).getValue());
    }

    @Test
    public void scopedZoneIsAnIsolationBoundary() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        MemoryScope driver = new MemoryScope("demo-driver", VehicleZone.DRIVER);
        MemoryScope passenger = new MemoryScope("demo-driver", VehicleZone.PASSENGER);
        store.putPreference(driver, "home", "公司");
        LegacyPreferenceMemorySource source = new LegacyPreferenceMemorySource(store);

        List<MemorySnippet> driverScoped = source.recallPreference(
                driver, "查电量", 5);
        List<MemorySnippet> passengerScoped = source.recallPreference(
                passenger, "查电量", 5);
        List<MemorySnippet> legacyScoped = source.recallPreference(
                MemoryScope.ofLegacy("demo-driver"), "查电量", 5);

        assertEquals(1, driverScoped.size());
        assertEquals("home", driverScoped.get(0).getKey());
        assertTrue(passengerScoped.isEmpty());
        assertTrue(legacyScoped.isEmpty());
    }

    @Test
    public void maxItemsZeroReturnsEmpty() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        store.putPreference("demo-driver", "k", "v");
        LegacyPreferenceMemorySource source = new LegacyPreferenceMemorySource(store);

        List<MemorySnippet> result = source.recallPreference(
                MemoryScope.ofLegacy("demo-driver"), "查电量", 0);

        assertTrue(result.isEmpty());
    }

    @Test
    public void constructorRejectsNullStore() {
        try {
            new LegacyPreferenceMemorySource(null);
            org.junit.Assert.fail("null memoryStore 必须拒绝");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}
