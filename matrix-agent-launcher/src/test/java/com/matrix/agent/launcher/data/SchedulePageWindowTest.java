package com.matrix.agent.launcher.data;

import static org.junit.Assert.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class SchedulePageWindowTest {
    @Test public void refreshKeepsLoadedWindowAndReplaysChangesFromEarliestPage() {
        var calls = new AtomicInteger();
        var result = SchedulePageWindow.read(3, cursor -> {
            calls.incrementAndGet();
            return cursor.isEmpty() ? new SchedulePageWindow.Page<>(List.of("a", "b"), "next", 10)
                    : new SchedulePageWindow.Page<>(List.of("b", "c"), "older", 12);
        }, value -> value);
        assertEquals(List.of("a", "b", "c"), result.items());
        assertEquals("older", result.nextCursor()); assertEquals(10, result.sequence());
        assertEquals(2, calls.get());
    }
    @Test public void exhaustedHistoryCanShrinkWithoutRetainingDeletedRows() {
        var result = SchedulePageWindow.read(100, ignored -> new SchedulePageWindow.Page<>(List.of("remaining"), "", 15), value -> value);
        assertEquals(List.of("remaining"), result.items()); assertEquals("", result.nextCursor());
    }
    @Test public void brokenCursorCannotLoopForever() {
        assertThrows(IllegalStateException.class, () -> SchedulePageWindow.read(100,
                ignored -> new SchedulePageWindow.Page<>(List.of("same"), "loop", 1), value -> value));
    }
}
