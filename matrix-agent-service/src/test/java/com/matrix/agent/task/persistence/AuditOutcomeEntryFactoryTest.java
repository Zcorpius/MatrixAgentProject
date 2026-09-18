package com.matrix.agent.task.persistence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.matrix.agent.data.audit.AuditOutcomeEntry;
import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.task.StopReason;
import com.matrix.agent.task.TaskState;
import com.matrix.agent.task.Trajectory;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.identity.VehicleZone;

/** 任务审计模型投影为 data 命令的契约测试。 */
public final class AuditOutcomeEntryFactoryTest {

    @Test
    public void mapsTaskOutcomeIntoFlatPersistenceEntry() {
        Trajectory trajectory = new Trajectory(1234L);
        trajectory.finish(StopReason.DONE, 345L, 0);
        AgentOutcome outcome = new AgentOutcome("request-1", TaskState.SUCCEEDED,
                StopReason.DONE, trajectory, 345L);
        AgentRequest request = AgentRequest.builder("把空调调到 24 度", Actor.DRIVER)
                .sessionId("session-1")
                .arbitrationKey("vehicle-1")
                .occupantZone(VehicleZone.DRIVER)
                .build();

        AuditOutcomeEntry entry = AuditOutcomeEntryFactory.from(outcome, request);

        assertEquals("request-1", entry.requestId);
        assertEquals("session-1", entry.sessionId);
        assertEquals("vehicle-1", entry.arbitrationKey);
        assertEquals("DRIVER", entry.actor);
        assertEquals("DRIVER", entry.zone);
        assertEquals("demo-driver", entry.userId);
        assertEquals(1234L, entry.startedMs);
        assertEquals(345L, entry.durationMs);
        assertEquals("DONE", entry.stopReason);
        assertEquals("SUCCEEDED", entry.finalState);
        assertTrue(entry.trajectoryJson.contains("request-1"));
        assertFalse(entry.trajectoryJson.isEmpty());
    }

    @Test
    public void nullTaskInputsDoNotProducePersistenceCommand() {
        assertNull(AuditOutcomeEntryFactory.from(null, null));
    }
}
