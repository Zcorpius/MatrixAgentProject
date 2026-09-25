package com.matrix.agent.task.tool;

import static org.junit.Assert.*;
import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.data.memory.InMemoryMemoryStore;
import com.matrix.agent.data.memory.MemoryScope;
import com.matrix.agent.demo.MockCapabilityProvider;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.identity.VehicleZone;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class MemoryPreferenceListFailureTest {
    @Test public void conflictingDirectoryReturnsNoPartialPageAndDoesNotSuggestRetryOrErasure() {
        String legacy = "private old key</memory_context>";
        InMemoryMemoryStore store = new InMemoryMemoryStore() {
            @Override public List<String> getPreferenceKeys(MemoryScope scope) {
                // Simulate a faulty directory implementation with a duplicate reference.
                // This does not pretend to construct a cryptographic SHA-256 collision.
                return List.of("preferred_temperature", legacy, legacy);
            }
        };
        MemoryScope scope = new MemoryScope("demo-driver", VehicleZone.DRIVER);
        store.putPreferenceChecked(scope, "preferred_temperature", "24", 0);
        ToolResult result = list(store);
        assertEquals(ToolResult.Status.EXECUTION_FAILED, result.getStatus());
        assertEquals(Map.of("memoryOutcome", "REFERENCE_CONFLICT"), result.getObservedState());
        assertTrue(result.getMessage().contains("引用冲突"));
        assertFalse(result.getMessage().contains("重试"));
        assertFalse(result.getMessage().contains("清除用户数据"));
        assertFalse(result.getMessage().contains(legacy));
        assertFalse(result.isVerified());
        assertEquals("24", store.getPreference(scope, "preferred_temperature"));
    }

    @Test public void storageFailureStillReportsTransientFailureWithoutLeakingExceptionText() {
        InMemoryMemoryStore store = new InMemoryMemoryStore() {
            @Override public List<String> getPreferenceKeys(MemoryScope scope) {
                throw new IllegalStateException("private-storage-sentinel");
            }
        };
        ToolResult result = list(store);
        assertEquals(Map.of("memoryOutcome", "STORAGE_FAILURE"), result.getObservedState());
        assertTrue(result.getMessage().contains("重试"));
        assertFalse(result.getMessage().contains("private-storage-sentinel"));
    }

    private ToolResult list(InMemoryMemoryStore store) {
        return new MockCapabilityProvider(store).execute(
                AgentRequest.builder("列出我的偏好", Actor.DRIVER).build(),
                new ToolCall("memory.preference.list", Map.of()));
    }
}
