package com.matrix.agent.task.tool;

import com.matrix.agent.demo.MockCapabilityProvider;

import com.matrix.agent.contract.ToolCall;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.matrix.agent.data.memory.MemoryWriter;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.identity.VehicleZone;
import com.matrix.agent.data.memory.InMemoryMemoryStore;

/**
 * 验证 MockCapabilityProvider.MemorySemanticSaveHandler 的 POLICY_REJECTED gate。
 *
 * <p>用户硬约束——"长期记忆仅由用户决定"。handler 在 request.isMemorySaveAllowed()=false 时
 * 直接返回 POLICY_REJECTED,writeSemantic 不被调用(双重防线:prompt 约定 + 硬 gate)。
 *
 * <p>验证:
 * <ul>
 *   <li>memorySaveAllowed=false → ToolResult.POLICY_REJECTED + writeSemantic 调 0 次</li>
 *   <li>memorySaveAllowed=true + writer 成功 → ToolResult.SUCCESS + writeSemantic 调 1 次</li>
 *   <li>memorySaveAllowed=true + writer 失败 → ToolResult.EXECUTION_FAILED(不被 gate 拦截)</li>
 *   <li>POLICY_REJECTED message 含"显式"/"记住"等引导词,帮助模型自我纠正</li>
 * </ul>
 */
public final class MemorySemanticSaveHandlerPolicyGateTest {

    @Test
    public void saveRejectedWhenMemorySaveFlagFalse() {
        RecordingWriter writer = new RecordingWriter(true);
        MockCapabilityProvider provider = new MockCapabilityProvider(
                new InMemoryMemoryStore(), writer);

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("key", "allergy.peanut");
        args.put("value", "花生过敏");
        ToolResult result = provider.execute(
                AgentRequest.builder("查天气", Actor.DRIVER)
                        .occupantZone(VehicleZone.DRIVER)
                        // 未通过 Repository 入口检测,默认 false
                        .memorySaveAllowed(false)
                        .build(),
                new ToolCall("memory.semantic.save", args));

        assertEquals("false → POLICY_REJECTED",
                ToolResult.Status.POLICY_REJECTED, result.getStatus());
        assertEquals("writeSemantic 不应被调(gate 在前)",
                0, writer.writeCount.get());
    }

    @Test
    public void saveAllowedWhenMemorySaveFlagTrue() {
        RecordingWriter writer = new RecordingWriter(true);
        MockCapabilityProvider provider = new MockCapabilityProvider(
                new InMemoryMemoryStore(), writer);

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("key", "allergy.peanut");
        args.put("value", "花生过敏");
        ToolResult result = provider.execute(
                AgentRequest.builder("记住我对花生过敏", Actor.DRIVER)
                        .occupantZone(VehicleZone.DRIVER)
                        .memorySaveAllowed(true)
                        .build(),
                new ToolCall("memory.semantic.save", args));

        assertEquals("true + writer 成功 → SUCCESS",
                ToolResult.Status.SUCCESS, result.getStatus());
        assertEquals("writeSemantic 调 1 次", 1, writer.writeCount.get());
    }

    @Test
    public void saveFailsWhenMemorySaveFlagTrueButWriterFails() {
        RecordingWriter writer = new RecordingWriter(false);
        MockCapabilityProvider provider = new MockCapabilityProvider(
                new InMemoryMemoryStore(), writer);

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("key", "allergy.peanut");
        args.put("value", "花生过敏");
        ToolResult result = provider.execute(
                AgentRequest.builder("记住我对花生过敏", Actor.DRIVER)
                        .memorySaveAllowed(true)
                        .build(),
                new ToolCall("memory.semantic.save", args));

        assertEquals("true + writer 失败 → EXECUTION_FAILED(非 POLICY_REJECTED)",
                ToolResult.Status.EXECUTION_FAILED, result.getStatus());
        assertEquals("writeSemantic 调 1 次(gate 放行,writer 内部失败)",
                1, writer.writeCount.get());
    }

    @Test
    public void policyRejectedMessageGuidesUserToBeExplicit() {
        RecordingWriter writer = new RecordingWriter(true);
        MockCapabilityProvider provider = new MockCapabilityProvider(
                new InMemoryMemoryStore(), writer);

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("key", "k");
        args.put("value", "v");
        ToolResult result = provider.execute(
                AgentRequest.builder("查天气", Actor.DRIVER).memorySaveAllowed(false).build(),
                new ToolCall("memory.semantic.save", args));

        String message = result.getMessage();
        assertTrue("message 含'记住'或'显式'引导词: " + message,
                message.contains("记住") || message.contains("显式"));
        assertFalse("POLICY_REJECTED 不应回写 memory",
                writer.writeCount.get() > 0);
    }

    private static final class RecordingWriter implements MemoryWriter {
        final AtomicInteger writeCount = new AtomicInteger();
        final boolean writeResult;

        RecordingWriter(boolean writeResult) {
            this.writeResult = writeResult;
        }

        @Override
        public void writeEpisodic(com.matrix.agent.identity.AgentRequest request, com.matrix.agent.data.memory.EpisodicWrite write) { }

        @Override
        public boolean writeSemantic(com.matrix.agent.identity.AgentRequest request, String key, String value, double score) {
            String userId = com.matrix.agent.identity.ActorUsers.userIdOf(request);
            String zone = request.getOccupantZone().wireValue();
            String sourceSessionId = request.getSessionId();
            long requestEpoch = request.getEpoch();
            writeCount.incrementAndGet();
            return writeResult;
        }

        @Override
        public String readSemantic(com.matrix.agent.identity.AgentRequest request, String key) {
            String userId = com.matrix.agent.identity.ActorUsers.userIdOf(request);
            String zone = request.getOccupantZone().wireValue();
            return null;
        }
    }
}
