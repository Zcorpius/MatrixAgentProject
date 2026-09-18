package com.matrix.agent.task.persistence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import com.matrix.agent.data.memory.EpisodicWrite;
import com.matrix.agent.data.memory.MemoryWriter;
import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.task.StopReason;
import com.matrix.agent.task.TaskState;
import com.matrix.agent.task.Trajectory;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.identity.VehicleZone;

/** 验证 task 侧适配器独占终态过滤及安全摘要到 data 命令的投影。 */
public final class EpisodicMemorySinkTest {

    @Test
    public void successfulOutcomeBecomesSafeFlatWriteCommand() {
        CapturingWriter writer = new CapturingWriter();
        EpisodicMemorySink sink = new EpisodicMemorySink(writer);
        AgentRequest request = AgentRequest.builder("我的地址是示例路 1 号", Actor.DRIVER)
                .sessionId("session-1")
                .occupantZone(VehicleZone.DRIVER)
                .build();
        Trajectory trajectory = new Trajectory(1000L);
        trajectory.finish(StopReason.DONE, 200L, 0);
        AgentOutcome outcome = new AgentOutcome(request.getRequestId(), TaskState.SUCCEEDED,
                StopReason.DONE, trajectory, 200L);

        sink.writeEpisodicOnTerminal(request, outcome, 7L);

        EpisodicWrite write = writer.write;
        assertNotNull(write);
        assertEquals(request.getRequestId(), write.requestId);
        assertEquals("demo-driver", write.userId);
        assertEquals("DRIVER", write.zone);
        assertEquals("session-1", write.sessionId);
        assertEquals(1000L, write.startedAtMillis);
        assertEquals("SUCCEEDED", write.finalState);
        assertEquals("DONE", write.stopReason);
        assertEquals(200L, write.durationMs);
        assertEquals(7L, write.requestEpoch);
        assertFalse("摘要不得包含原始用户文本", write.summaryJson.contains("示例路 1 号"));
    }

    @Test
    public void cancelledOutcomeIsNotForwardedToDataLayer() {
        CapturingWriter writer = new CapturingWriter();
        EpisodicMemorySink sink = new EpisodicMemorySink(writer);
        AgentRequest request = AgentRequest.builder("取消导航到医院", Actor.DRIVER).build();
        Trajectory trajectory = new Trajectory(1000L);
        trajectory.finish(StopReason.CANCELLED, 200L, 0);
        AgentOutcome outcome = new AgentOutcome(request.getRequestId(), TaskState.CANCELLED,
                StopReason.CANCELLED, trajectory, 200L);

        sink.writeEpisodicOnTerminal(request, outcome, 7L);

        assertNull(writer.write);
    }

    private static final class CapturingWriter implements MemoryWriter {
        private EpisodicWrite write;

        @Override
        public void writeEpisodic(EpisodicWrite write) {
            this.write = write;
        }

        @Override
        public boolean writeSemantic(String userId, String zone, String key, String value,
                double score, String sourceSessionId, long requestEpoch) {
            return false;
        }

        @Override
        public String readSemantic(String userId, String zone, String key) {
            return null;
        }
    }
}
