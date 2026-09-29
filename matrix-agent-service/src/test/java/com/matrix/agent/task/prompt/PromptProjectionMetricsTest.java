package com.matrix.agent.task.prompt;

import com.matrix.agent.data.memory.*;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;
import static com.matrix.agent.task.prompt.PromptProjectionMetrics.BudgetBranch.*;

public final class PromptProjectionMetricsTest {
    private static final List<MemorySnippet> MEMORY = List.of(MemorySnippet.of(MemoryLayer.SEMANTIC,
            MemoryScope.ofLegacy("test"), "fact.topic", "must stay private"));
    @Test public void countsActualKeysAndWholeMemoryRemovalSeparately() {
        String base = "rules" + DefaultPromptBuilder.formatRecalledMemory(MEMORY);
        var full = PromptContextAssembler.assemble(base, "skill", 1000);
        var metrics = PromptProjectionMetrics.measure(MEMORY, base, "skill", full.text(), full.branch());
        assertEquals(1, metrics.eligibleSemanticKeys()); assertEquals(1, metrics.retainedSemanticKeys());
        assertTrue(metrics.skillRetained()); assertFalse(metrics.memoryRemoved());
        var dropped = PromptContextAssembler.assemble(base, "skill", 20);
        metrics = PromptProjectionMetrics.measure(MEMORY, base, "skill", dropped.text(), dropped.branch());
        assertEquals(DROP_MEMORY, metrics.branch()); assertEquals(0, metrics.retainedSemanticKeys());
        assertTrue(metrics.memoryRemoved()); assertTrue(metrics.skillRetained());
        var noSkill = PromptContextAssembler.assemble(base, "", 1000);
        metrics = PromptProjectionMetrics.measure(MEMORY, base, "", noSkill.text(), noSkill.branch());
        assertFalse(metrics.skillSelected()); assertFalse(metrics.skillRetained()); assertEquals(NO_SKILL, metrics.branch());
    }
    @Test public void coversBelowEqualAndAboveMinimumBaseRoom() {
        String base = "B".repeat(400), skill = "S".repeat(100);
        for (int room : new int[]{255,256,257}) {
            var assembly = PromptContextAssembler.assemble(base, skill, room + skill.length());
            assertEquals(room < 256 ? BASE_ONLY : TRIM_BASE, assembly.branch());
            var metrics = PromptProjectionMetrics.measure(List.of(), base, skill, assembly.text(), assembly.branch());
            assertEquals(room >= 256, metrics.skillRetained());
            assertTrue(assembly.text().length() <= room + skill.length());
        }
        assertEquals(FULL, PromptContextAssembler.assemble(base, skill, 500).branch());
    }
}
