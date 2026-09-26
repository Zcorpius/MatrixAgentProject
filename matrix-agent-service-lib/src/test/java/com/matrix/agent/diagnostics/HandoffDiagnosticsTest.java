package com.matrix.agent.diagnostics;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.matrix.agent.diagnostics.HandoffDiagnostics.Stage.*;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class HandoffDiagnosticsTest {
    @Test public void quantilesIncludeTimeoutsAndSeparateModes() {
        var diagnostics = new HandoffDiagnostics();
        for (int i = 1; i <= 100; i++) diagnostics.record(HANDOFF, 2, i <= 90 ? 1 : 8,
                0, i <= 90, i, i, "task", "request");
        diagnostics.record(HANDOFF, 1, 1, 0, true, 101, 1200, "task", "first");
        var summary = diagnostics.snapshot().summaries().get(0);
        assertEquals(100, summary.count()); assertEquals(90, summary.successes());
        assertEquals(50, summary.p50Ms()); assertEquals(95, summary.p95Ms());
        assertEquals(Long.valueOf(10), summary.results().get(8));
        assertEquals(1200, diagnostics.snapshot().summaries().get(1).p95Ms());
    }
    @Test public void retentionIsBoundedWithoutLosingLifetimeCounts() {
        var diagnostics = new HandoffDiagnostics();
        for (int i = 0; i < 1000; i++) diagnostics.record(WINDOW_ATTACH, 1, 1, 0, true, i, i, null, null);
        var snapshot = diagnostics.snapshot();
        assertEquals(256, snapshot.events().size());
        var summary = snapshot.summaries().get(0);
        assertEquals(1000, summary.count()); assertEquals(256, summary.timedSamples());
        assertEquals(987, summary.p95Ms());
        assertEquals(744, snapshot.events().get(0).durationMs());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.events().clear());
    }
    @Test public void untimedOutcomesDoNotInventLatencyAndExportContainsOnlyDigests() {
        var diagnostics = new HandoffDiagnostics();
        diagnostics.record(TASK_TERMINAL, 0, 2, 0, true, 42, -1, "private-task\"\n", "private-request");
        var row = diagnostics.snapshot().summaries().get(0);
        assertEquals(0, row.timedSamples()); assertEquals(-1, row.p95Ms());
        StringWriter text = new StringWriter(); diagnostics.dump(new PrintWriter(text));
        assertEquals(3, text.toString().lines().count());
        assertFalse(text.toString().contains("private-"));
        assertTrue(text.toString().contains("\"stage\":\"TASK_TERMINAL\""));
        assertTrue(diagnostics.snapshot().events().get(0).task().matches("[0-9a-f]{64}"));
    }
    @Test public void concurrentRecordingAndSnapshotsPreserveCounts() throws Exception {
        var diagnostics = new HandoffDiagnostics();
        var workers = Executors.newFixedThreadPool(4);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int n = 0; n < 4; n++) futures.add(workers.submit(() -> {
                for (int i = 0; i < 200; i++) {
                    diagnostics.record(HANDOFF, 2, 1, 0, true, i, 4, null, null);
                    diagnostics.snapshot();
                }
            }));
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
            assertEquals(800, diagnostics.snapshot().summaries().get(0).count());
        } finally { workers.shutdownNow(); }
    }
    @Test public void disabledDiagnosticsRetainNothing() {
        HandoffDiagnostics.NONE.record(HANDOFF, 1, 1, 0, true, 1, 1, "task", "request");
        assertTrue(HandoffDiagnostics.NONE.snapshot().events().isEmpty());
    }
}
