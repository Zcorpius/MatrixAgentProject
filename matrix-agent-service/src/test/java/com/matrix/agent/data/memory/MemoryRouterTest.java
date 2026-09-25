package com.matrix.agent.data.memory;

import com.matrix.agent.session.SessionManager;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.identity.VehicleZone;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * MemoryRouter 召回契约测试——按 layer 优先级合并 + maxItems 截断。
 *
 * <p>用 fake source 验证 Router 本身行为,不依赖具体 source 实现(SessionManager / MemoryStore)。
 */
public final class MemoryRouterTest {

    private static MemorySnippet snippet(MemoryLayer layer, String key) {
        return new MemorySnippet(layer, new MemoryScope("demo-driver", VehicleZone.DRIVER),
                key, "v-" + key, 1.0, 0L, null);
    }

    private static WorkingMemorySource working(MemorySnippet... snippets) {
        return (scope, sessionId, userText, maxItems) -> limit(snippets, maxItems);
    }

    private static EpisodicMemorySource episodic(MemorySnippet... snippets) {
        return (scope, userText, maxItems) -> limit(snippets, maxItems);
    }

    private static SemanticMemorySource semantic(MemorySnippet... snippets) {
        return (scope, userText, maxItems) -> limit(snippets, maxItems);
    }

    private static PreferenceMemorySource preference(MemorySnippet... snippets) {
        return (scope, userText, maxItems) -> limit(snippets, maxItems);
    }

    private static List<MemorySnippet> limit(MemorySnippet[] snippets, int maxItems) {
        if (maxItems <= 0) return Collections.emptyList();
        int n = Math.min(snippets.length, maxItems);
        List<MemorySnippet> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) result.add(snippets[i]);
        return result;
    }

    @Test
    public void emptySourcesReturnEmpty() {
        MemoryRouter router = new MemoryRouter(
                working(), episodic(), semantic(), preference());
        List<MemorySnippet> result = router.recall(
                new MemoryScope("demo-driver", VehicleZone.DRIVER), "session-1", "查电量", 5);
        assertTrue(result.isEmpty());
    }

    @Test
    public void workingPlaceholderDoesNotConsumePreferenceSlots() {
        MemorySnippet w1 = snippet(MemoryLayer.WORKING, "w-1");
        MemorySnippet w2 = snippet(MemoryLayer.WORKING, "w-2");
        MemorySnippet p1 = snippet(MemoryLayer.PREFERENCE, "p_1");
        MemoryRouter router = new MemoryRouter(
                working(w1, w2), episodic(), semantic(), preference(p1));

        List<MemorySnippet> result = router.recall(
                new MemoryScope("demo-driver", VehicleZone.DRIVER), "session-1", "查电量", 2);

        assertEquals(1, result.size());
        assertEquals(MemoryLayer.PREFERENCE, result.get(0).getLayer());
        assertEquals("p_1", result.get(0).getKey());
    }

    @Test
    public void preferenceFillsRemainingSlotsWhenWorkingEmpty() {
        MemorySnippet p1 = snippet(MemoryLayer.PREFERENCE, "p_1");
        MemorySnippet p2 = snippet(MemoryLayer.PREFERENCE, "p_2");
        MemoryRouter router = new MemoryRouter(
                working(), episodic(), semantic(), preference(p1, p2));

        List<MemorySnippet> result = router.recall(
                new MemoryScope("demo-driver", VehicleZone.DRIVER), "session-1", "查电量", 5);

        assertEquals(2, result.size());
        assertEquals(MemoryLayer.PREFERENCE, result.get(0).getLayer());
        assertEquals("p_1", result.get(0).getKey());
        assertEquals("p_2", result.get(1).getKey());
    }

    @Test
    public void preferencesBorrowUnusedSlotsWithoutRepeatingEntries() {
        MemorySnippet[] prefs = new MemorySnippet[6];
        for (int i = 0; i < prefs.length; i++) {
            prefs[i] = snippet(MemoryLayer.PREFERENCE, "pref_" + i);
        }
        MemoryRouter router = new MemoryRouter(
                working(), episodic(), semantic(), preference(prefs));

        List<MemorySnippet> result = router.recall(
                new MemoryScope("demo-driver", VehicleZone.DRIVER), "session-1", "查电量", 5);

        assertEquals(5, result.size());
        for (int i = 0; i < result.size(); i++) {
            assertEquals("pref_" + i, result.get(i).getKey());
        }
    }

    @Test
    public void allLayersMergeInPriorityOrder() {
        MemorySnippet w = snippet(MemoryLayer.WORKING, "w-1");
        MemorySnippet e = snippet(MemoryLayer.EPISODIC, "recent_task.climate.succeeded");
        MemorySnippet s = snippet(MemoryLayer.SEMANTIC, "fact.test");
        MemorySnippet p = snippet(MemoryLayer.PREFERENCE, "p_1");
        MemoryRouter router = new MemoryRouter(
                working(w), episodic(e), semantic(s), preference(p));

        List<MemorySnippet> result = router.recall(
                new MemoryScope("demo-driver", VehicleZone.DRIVER), "session-1", "查电量", 4);

        assertEquals(Arrays.asList(p, s, e), result);
    }

    @Test
    public void verifiedWorkingFactUsesOneSlotAfterPreference() {
        MemorySnippet workingFact = new MemorySnippet(MemoryLayer.WORKING,
                new MemoryScope("demo-driver", VehicleZone.DRIVER),
                "last_climate_temperature", "driver:24", 1.0, 0L, "session-1");
        MemorySnippet preferenceFact = snippet(MemoryLayer.PREFERENCE, "preferred_temperature");
        MemoryRouter router = new MemoryRouter(working(workingFact), episodic(),
                semantic(), preference(preferenceFact));
        List<MemorySnippet> recalled = router.recall(
                new MemoryScope("demo-driver", VehicleZone.DRIVER), "session-1", "刚才空调多少度", 2);
        assertEquals(Arrays.asList(preferenceFact, workingFact), recalled);
    }

    @Test
    public void maxItemsZeroReturnsEmpty() {
        MemorySnippet w = snippet(MemoryLayer.WORKING, "w-1");
        MemoryRouter router = new MemoryRouter(
                working(w), episodic(), semantic(), preference());

        List<MemorySnippet> result = router.recall(
                new MemoryScope("demo-driver", VehicleZone.DRIVER), "session-1", "查电量", 0);

        assertTrue(result.isEmpty());
    }

    @Test
    public void nullScopeReturnsEmpty() {
        MemorySnippet w = snippet(MemoryLayer.WORKING, "w-1");
        MemoryRouter router = new MemoryRouter(
                working(w), episodic(), semantic(), preference());

        List<MemorySnippet> result = router.recall(null, "session-1", "查电量", 5);

        assertTrue(result.isEmpty());
    }

    @Test
    public void constructorRejectsNullSources() {
        try {
            new MemoryRouter(null, episodic(), semantic(), preference());
            org.junit.Assert.fail("working source null 必须拒绝");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            new MemoryRouter(working(), null, semantic(), preference());
            org.junit.Assert.fail("episodic source null 必须拒绝");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            new MemoryRouter(working(), episodic(), null, preference());
            org.junit.Assert.fail("semantic source null 必须拒绝");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            new MemoryRouter(working(), episodic(), semantic(), null);
            org.junit.Assert.fail("preference source null 必须拒绝");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}
