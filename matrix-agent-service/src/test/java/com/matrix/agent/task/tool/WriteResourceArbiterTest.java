package com.matrix.agent.task.tool;

import static org.junit.Assert.*;
import java.util.concurrent.*;
import org.junit.Test;

public final class WriteResourceArbiterTest {
    @Test public void relatedWritesWaitForActualProviderExitAndIndependentDomainsRemainAvailable() throws Exception {
        var arbiter = new WriteResourceArbiter();
        var contender = Executors.newSingleThreadExecutor();
        try {
            try (var lease = arbiter.acquire("calendar.create", 0)) {
                assertNotNull(lease);
                assertFalse(contender.submit(() -> {
                    try (var blocked = arbiter.acquire("calendar.delete", 20)) { return blocked != null; }
                }).get(1, TimeUnit.SECONDS));
                assertTrue(contender.submit(() -> {
                    try (var independent = arbiter.acquire("clock.open", 0)) { return independent != null; }
                }).get(1, TimeUnit.SECONDS));
            }
            assertTrue(contender.submit(() -> {
                try (var released = arbiter.acquire("calendar.update", 0)) { return released != null; }
            }).get(1, TimeUnit.SECONDS));
        } finally { contender.shutdownNow(); }
    }
}
