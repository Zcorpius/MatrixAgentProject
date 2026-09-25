package com.matrix.agent.task.tool;

import com.matrix.agent.demo.MockCapabilityProvider;

import com.matrix.agent.contract.ToolCall;

import static org.junit.Assert.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.data.memory.MemoryWriter;
import com.matrix.agent.task.StopReason;
import com.matrix.agent.task.TaskState;
import com.matrix.agent.task.Trajectory;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.identity.VehicleZone;
import com.matrix.agent.data.memory.InMemoryMemoryStore;

/**
 * 验证 MockCapabilityProvider.MemorySemanticSaveHandler 把
 * request.getEpoch() 透传到 MemoryWriter.writeSemantic 第 7 参 requestEpoch。
 *
 * <p>用户硬约束:epoch 必须与数据库写操作同事务。handler 不直接做 epoch 校验,
 * 但必须**透传正确的 epoch 值**给 RoomMemoryWriter,后者在 Room 事务内做比较。
 *
 * <p>验证:
 * <ul>
 *   <li>request.epoch=N → writeSemantic 收到 requestEpoch=N</li>
 *   <li>request.epoch=0(默认)→ writeSemantic 收到 requestEpoch=0</li>
 *   <li>跨多次 save 调用,每次都透传当时的 request.epoch</li>
 * </ul>
 */
public final class MockCapabilityProviderSemanticEpochPropTest {

    @Test
    public void semanticSavePropagatesRequestEpoch() {
        RecordingWriter writer = new RecordingWriter();
        MockCapabilityProvider provider = new MockCapabilityProvider(
                new InMemoryMemoryStore(), writer);

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("key", "allergy.peanut");
        args.put("value", "花生过敏");
        provider.execute(
                AgentRequest.builder("记住我对花生过敏", Actor.DRIVER)
                        .sessionId("sess-epoch-5")
                        .occupantZone(VehicleZone.DRIVER)
                        .epoch(5L)
                        // 跨过 handler POLICY_REJECTED gate(单测直接 build,不走 Repository 检测)
                        .memorySaveAllowed(true)
                        .build(),
                new ToolCall("memory.semantic.save", args));

        assertEquals("requestEpoch=5 透传到 writeSemantic", 5L, writer.lastRequestEpoch.get());
    }

    @Test
    public void semanticSavePropagatesZeroEpochByDefault() {
        RecordingWriter writer = new RecordingWriter();
        MockCapabilityProvider provider = new MockCapabilityProvider(
                new InMemoryMemoryStore(), writer);

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("key", "allergy.peanut");
        args.put("value", "花生过敏");
        provider.execute(
                AgentRequest.builder("记住我花生过敏", Actor.DRIVER)
                        .memorySaveAllowed(true).build(),
                new ToolCall("memory.semantic.save", args));

        assertEquals("默认 epoch=0 透传", 0L, writer.lastRequestEpoch.get());
    }

    @Test
    public void semanticSavePropagatesUpdatedEpochAcrossRequests() {
        RecordingWriter writer = new RecordingWriter();
        MockCapabilityProvider provider = new MockCapabilityProvider(
                new InMemoryMemoryStore(), writer);

        // 第一次:epoch=2
        Map<String, Object> args1 = new LinkedHashMap<>();
        args1.put("key", "allergy.peanut");
        args1.put("value", "花生过敏");
        provider.execute(
                AgentRequest.builder("记住我花生过敏", Actor.DRIVER).epoch(2L).memorySaveAllowed(true).build(),
                new ToolCall("memory.semantic.save", args1));

        // 第二次:epoch=3(模拟 clearUserData bump 后的新请求)
        Map<String, Object> args2 = new LinkedHashMap<>();
        args2.put("key", "work.role");
        args2.put("value", "工程师");
        provider.execute(
                AgentRequest.builder("记住我的工作是工程师", Actor.DRIVER).epoch(3L).memorySaveAllowed(true).build(),
                new ToolCall("memory.semantic.save", args2));

        assertEquals("2 次 save 都被调", 2, writer.writeCount.get());
        assertEquals("第 2 次 requestEpoch=3 透传", 3L, writer.lastRequestEpoch.get());
    }

    private static final class RecordingWriter implements MemoryWriter {
        final AtomicInteger writeCount = new AtomicInteger();
        final AtomicLong lastRequestEpoch = new AtomicLong(-1L);

        @Override
        public void writeEpisodic(com.matrix.agent.identity.AgentRequest request, com.matrix.agent.data.memory.EpisodicWrite write) { }

        @Override
        public boolean writeSemantic(com.matrix.agent.identity.AgentRequest request, String key, String value, double score) {
            String userId = com.matrix.agent.identity.ActorUsers.userIdOf(request);
            String zone = request.getOccupantZone().wireValue();
            String sourceSessionId = request.getSessionId();
            long requestEpoch = request.getEpoch();
            writeCount.incrementAndGet();
            lastRequestEpoch.set(requestEpoch);
            return true;
        }

        @Override
        public String readSemantic(com.matrix.agent.identity.AgentRequest request, String key) {
            String userId = com.matrix.agent.identity.ActorUsers.userIdOf(request);
            String zone = request.getOccupantZone().wireValue();
            return null;
        }
    }
}
