package com.matrix.agent.task.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.matrix.agent.data.memory.MemoryWriter;
import com.matrix.agent.task.identity.Actor;
import com.matrix.agent.task.identity.AgentRequest;
import com.matrix.agent.task.identity.VehicleZone;
import com.matrix.agent.data.memory.InMemoryMemoryStore;

/**
 * MockCapabilityProvider 注册 memory.semantic.save / get 后,
 * handler 调用 ctx.getMemoryWriter() 写入 / 读取,并按结果返回 SUCCESS / EXECUTION_FAILED。
 *
 * <p>验证:
 * <ul>
 *   <li>save 成功 → ToolResult.SUCCESS + memoryWriter.writeSemantic 被调 1 次</li>
 *   <li>save 失败(writer 返回 false)→ EXECUTION_FAILED</li>
 *   <li>get 命中 → SUCCESS + message="已找到这条记忆"</li>
 *   <li>get 未命中(writer 返回 null)→ EXECUTION_FAILED + message="还没有保存这条记忆"</li>
 * </ul>
 */
public final class MockCapabilityProviderSemanticHandlerTest {

    @Test
    public void semanticSaveCallsWriterAndReturnsSuccess() {
        RecordingWriter writer = new RecordingWriter(/*writeResult=*/true);
        MockCapabilityProvider provider = new ProviderBuilder()
                .memoryWriter(writer)
                .build();

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("key", "allergy.peanut");
        args.put("value", "严重过敏");
        ToolResult result = provider.execute(
                AgentRequest.builder("记住我对花生过敏", Actor.DRIVER)
                        .sessionId("sess-001")
                        .occupantZone(VehicleZone.DRIVER)
                        // Repository 入口由 KeywordMemoryIntentDetector 命中关键词置 true;
                        // 单测直接 build request,需显式置 true 才能跨过 handler POLICY_REJECTED gate。
                        .memorySaveAllowed(true)
                        .build(),
                new ToolCall("memory.semantic.save", args));

        assertEquals(ToolResult.Status.SUCCESS, result.getStatus());
        assertEquals("memory.semantic.save", result.getCapabilityName());
        assertEquals("writeSemantic 调 1 次", 1, writer.writeCount.get());
        assertEquals("allergy.peanut", writer.lastKey);
        assertEquals("严重过敏", writer.lastValue);
        assertEquals("demo-driver", writer.lastUserId);
        assertEquals("DRIVER", writer.lastZone);
        assertEquals("sess-001", writer.lastSessionId);
    }

    @Test
    public void semanticSaveWriterReturnsFalseTranslatesToExecutionFailed() {
        RecordingWriter writer = new RecordingWriter(/*writeResult=*/false);
        MockCapabilityProvider provider = new ProviderBuilder()
                .memoryWriter(writer)
                .build();

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("key", "allergy.peanut");
        args.put("value", "严重过敏");
        ToolResult result = provider.execute(
                AgentRequest.builder("记住我对花生过敏", Actor.DRIVER)
                        .memorySaveAllowed(true)
                        .build(),
                new ToolCall("memory.semantic.save", args));

        assertEquals(ToolResult.Status.EXECUTION_FAILED, result.getStatus());
        assertEquals(1, writer.writeCount.get());
    }

    @Test
    public void semanticGetWriterReturnsValueTranslatesToSuccess() {
        RecordingWriter writer = new RecordingWriter(/*writeResult=*/true);
        writer.preloadValue = "我女儿叫小红";
        MockCapabilityProvider provider = new ProviderBuilder()
                .memoryWriter(writer)
                .build();

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("key", "fact.daughter_name");
        ToolResult result = provider.execute(
                AgentRequest.builder("我女儿叫什么", Actor.DRIVER)
                        .sessionId("sess-002")
                        .occupantZone(VehicleZone.DRIVER)
                        .build(),
                new ToolCall("memory.semantic.get", args));

        assertEquals(ToolResult.Status.SUCCESS, result.getStatus());
        assertEquals("memory.semantic.get", result.getCapabilityName());
        assertEquals("readSemantic 调 1 次", 1, writer.readCount.get());
        assertEquals("fact.daughter_name", writer.lastKey);
    }

    @Test
    public void semanticGetWriterReturnsNullTranslatesToExecutionFailed() {
        RecordingWriter writer = new RecordingWriter(/*writeResult=*/true);
        writer.preloadValue = null;
        MockCapabilityProvider provider = new ProviderBuilder()
                .memoryWriter(writer)
                .build();

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("key", "fact.daughter_name");
        ToolResult result = provider.execute(
                AgentRequest.builder("我女儿叫什么", Actor.DRIVER).build(),
                new ToolCall("memory.semantic.get", args));

        assertEquals(ToolResult.Status.EXECUTION_FAILED, result.getStatus());
        assertEquals(1, writer.readCount.get());
    }

    @Test
    public void singleArgConstructorDefaultsWriterToNoOpAndSemanticSaveFails() {
        // 单参构造器 delegate memoryWriter=NOOP —— writeSemantic 永远返回 false
        MockCapabilityProvider provider = new MockCapabilityProvider(new InMemoryMemoryStore());

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("key", "allergy.peanut");
        args.put("value", "严重过敏");
        ToolResult result = provider.execute(
                AgentRequest.builder("记住我对花生过敏", Actor.DRIVER)
                        .memorySaveAllowed(true)
                        .build(),
                new ToolCall("memory.semantic.save", args));

        assertEquals("NOOP writer → EXECUTION_FAILED",
                ToolResult.Status.EXECUTION_FAILED, result.getStatus());
    }

    @Test
    public void singleArgConstructorSemanticGetFailsOnNoOp() {
        MockCapabilityProvider provider = new MockCapabilityProvider(new InMemoryMemoryStore());

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("key", "anything");
        ToolResult result = provider.execute(
                AgentRequest.builder("查询", Actor.DRIVER).build(),
                new ToolCall("memory.semantic.get", args));

        assertEquals("NOOP writer → readSemantic=null → EXECUTION_FAILED",
                ToolResult.Status.EXECUTION_FAILED, result.getStatus());
    }

    private static final class ProviderBuilder {
        private MemoryWriter memoryWriter = MemoryWriter.NOOP;

        ProviderBuilder memoryWriter(MemoryWriter w) {
            this.memoryWriter = w;
            return this;
        }

        MockCapabilityProvider build() {
            return new MockCapabilityProvider(new InMemoryMemoryStore(), memoryWriter);
        }
    }

    private static final class RecordingWriter implements MemoryWriter {
        final AtomicInteger writeCount = new AtomicInteger();
        final AtomicInteger readCount = new AtomicInteger();
        final boolean writeResult;
        String preloadValue;
        String lastKey;
        String lastValue;
        String lastUserId;
        String lastZone;
        String lastSessionId;
        long lastRequestEpoch = -1L;

        RecordingWriter(boolean writeResult) {
            this.writeResult = writeResult;
        }

        @Override
        public void writeEpisodicOnTerminal(AgentRequest request,
                com.matrix.agent.task.AgentOutcome outcome, long requestEpoch) { }

        @Override
        public boolean writeSemantic(String userId, String zone, String key, String value,
                double score, String sourceSessionId, long requestEpoch) {
            writeCount.incrementAndGet();
            lastKey = key;
            lastValue = value;
            lastUserId = userId;
            lastZone = zone;
            lastSessionId = sourceSessionId;
            lastRequestEpoch = requestEpoch;
            return writeResult;
        }

        @Override
        public String readSemantic(String userId, String zone, String key) {
            readCount.incrementAndGet();
            lastKey = key;
            lastUserId = userId;
            lastZone = zone;
            return preloadValue;
        }
    }
}
