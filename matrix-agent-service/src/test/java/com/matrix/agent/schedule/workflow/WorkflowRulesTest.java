package com.matrix.agent.schedule.workflow;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;

public final class WorkflowRulesTest {
    @Test public void parallelIntervalsAreChargedOnceAndWaitingCostsNothing() {
        long spent = ActiveBudget.charge(0, 1000L, 20_000);
        assertEquals(19_000, spent);
        // Second child started within the same interval: the ledger has one anchor, not two sums.
        spent = ActiveBudget.charge(spent, 20_000L, 31_000);
        assertEquals(30_000, spent);
        assertEquals(spent, ActiveBudget.charge(spent, null, 100_000));
        assertEquals(40_000, ActiveBudget.allowance(spent, 40_000, 100_000, 200_000));
        assertEquals(10_000, ActiveBudget.allowance(110_000, 60_000, 100_000, 200_000));
        assertEquals(0, ActiveBudget.allowance(120_000, 60_000, 100_000, 200_000));
        assertEquals(500, ActiveBudget.allowance(0, 60_000, 100_000, 100_500));
    }
    @Test public void cyclicAndWriteRetryGraphsAreRejected() {
        var cycle = new WorkflowTemplate.Step("self", "self", WorkflowTemplate.Kind.DELIVER, List.of("self"), true, false, "", 1000, 0, "delivery");
        assertThrows(IllegalArgumentException.class, () -> new WorkflowTemplate("test", 1, "", "", List.of(cycle)));
        var unsafe = new WorkflowTemplate.Step("write", "write", WorkflowTemplate.Kind.DELIVER, List.of(), true, false, "", 1000, 1, "delivery");
        assertThrows(IllegalArgumentException.class, () -> new WorkflowTemplate("test", 1, "", "", List.of(unsafe)));
    }
    @Test public void shippedTemplatesHaveFrozenVersionsAndBoundedSteps() {
        for (var descriptor : WorkflowCatalog.describe()) {
            var template = WorkflowCatalog.require(descriptor.templateId, descriptor.version);
            assertTrue(template.steps().size() <= 8);
            assertEquals(40_000, template.steps().stream().filter(s -> s.id().equals("summary")).findFirst().orElseThrow().timeoutMillis());
        }
        assertThrows(IllegalArgumentException.class, () -> WorkflowCatalog.require("daily_agenda", 2));
    }
}
