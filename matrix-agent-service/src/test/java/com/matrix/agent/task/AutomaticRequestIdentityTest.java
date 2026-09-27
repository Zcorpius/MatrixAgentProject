package com.matrix.agent.task;

import static org.junit.Assert.*;
import com.matrix.agent.data.memory.InMemoryMemoryStore;
import com.matrix.agent.identity.*;
import com.matrix.agent.task.scheduler.*;
import com.matrix.agent.vehicle.VehicleState;
import java.util.Set;
import java.util.UUID;
import org.junit.Test;

public final class AutomaticRequestIdentityTest {
    private static ExecutionScope scope() {
        return ExecutionScope.automatic(Set.of("calendar.query"), false, new ExecutionScope.Guard() {
            public String rejection() { return ""; }
            public long remainingMillis() { return 40000; }
            public boolean reserveTool() { return true; }
        });
    }
    @Test public void shortAndLongTriggersPreserveActorZoneAndUseIndependentStableSessions() {
        var memory = new InMemoryMemoryStore();
        var factory = new TaskRequestFactory(memory, new AgentBudget(), VehicleState::satisfyAllPredicates, null);
        for (Actor actor : Actor.values()) for (int length : new int[]{1, 512, 513}) {
            String requestId = UUID.randomUUID().toString();
            VehicleZone zone = actor == Actor.DRIVER ? VehicleZone.DRIVER : VehicleZone.PASSENGER;
            var authority = scope();
            var task = new PreparedAutomaticTask(requestId, UUID.randomUUID().toString(), "x".repeat(length),
                    actor, zone, memory.currentEpoch(), 40000, true, authority);
            var request = factory.newAutomaticRequestBuilder(task, new CancellationToken()).build();
            assertEquals(requestId, request.getRequestId());
            assertEquals("schedule-" + requestId, request.getSessionId());
            assertEquals(actor, request.getActor()); assertEquals(zone, request.getOccupantZone());
            assertEquals(InputSource.SCHEDULED, request.getInputSource());
            assertEquals(length, request.getText().length()); assertSame(authority, request.getExecutionScope());
            assertEquals(40000, request.getDeadlineAtMillis() - request.getCreatedAtMillis());
            assertNull(request.getInteractiveOrigin());
        }
    }
    @Test public void queuedRequestCannotCrossDataClearOrChangeOccupantZone() {
        var memory = new InMemoryMemoryStore();
        var factory = new TaskRequestFactory(memory, new AgentBudget(), VehicleState::satisfyAllPredicates, null);
        var task = new PreparedAutomaticTask(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "goal",
                Actor.PASSENGER, VehicleZone.PASSENGER, memory.currentEpoch(), 40000, true, scope());
        memory.bumpEpoch();
        assertThrows(IllegalStateException.class, () -> factory.newAutomaticRequestBuilder(task, new CancellationToken()));
        assertThrows(IllegalArgumentException.class, () -> new PreparedAutomaticTask(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "goal",
                Actor.PASSENGER, VehicleZone.DRIVER, memory.currentEpoch(), 40000, true, scope()));
    }
}
