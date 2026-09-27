package com.matrix.agent.identity;

import static org.junit.Assert.*;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class ExecutionScopeTest {
    @Test public void authorizationAndParentReservationsRemainBounded() {
        AtomicInteger parent = new AtomicInteger(3); AtomicBoolean revoked = new AtomicBoolean();
        ExecutionScope scope = ExecutionScope.automatic(Set.of("calendar.query"), false, new ExecutionScope.Guard() {
            public String rejection() { return revoked.get() ? "REVOKED" : ""; }
            public long remainingMillis() { return 40_000; }
            public boolean reserveTool() { return parent.getAndDecrement() > 0; }
        });
        assertTrue(scope.allows("calendar.query")); assertFalse(scope.allows("calendar.create")); assertFalse(scope.networkAllowed());
        assertTrue(scope.reserveTool()); assertTrue(scope.reserveTool()); assertTrue(scope.reserveTool()); assertFalse(scope.reserveTool());
        revoked.set(true); assertEquals("REVOKED", scope.rejection()); assertFalse(scope.reserveTool());
    }
    @Test public void localChildBudgetIsNotRenewedByRepeatedCalls() {
        ExecutionScope scope = ExecutionScope.automatic(Set.of(), true, new ExecutionScope.Guard() {
            public String rejection() { return ""; } public long remainingMillis() { return 60_000; } public boolean reserveTool() { return true; }
        });
        for (int i = 0; i < 8; i++) assertTrue(scope.reserveTool());
        assertFalse(scope.reserveTool()); assertEquals(8, scope.toolCalls());
    }
}
