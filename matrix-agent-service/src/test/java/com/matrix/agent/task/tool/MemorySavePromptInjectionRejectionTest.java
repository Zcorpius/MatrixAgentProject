package com.matrix.agent.task.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.matrix.agent.data.memory.MemoryWriter;
import com.matrix.agent.task.identity.Actor;
import com.matrix.agent.task.identity.AgentRequest;
import com.matrix.agent.data.memory.InMemoryMemoryStore;

/**
 * 模拟提示注入场景——用户命令是纯查询("查天气"),模型仍尝试调
 * memory.semantic.save(可能被恶意网页/上下文诱导)。Detector 未命中关键词 →
 * memorySaveAllowed=false → handler 直接 POLICY_REJECTED,writeSemantic 不被调。
 *
 * <p>这是核心安全语义:prompt 约定(BASE_TEMPLATE 文案 + capability description)
 * 不再是唯一防线——即便模型被诱导调用,硬 gate 也守住。DUAL-DEFENSE(prompt + code)。
 */
public final class MemorySavePromptInjectionRejectionTest {

    @Test
    public void pureQueryCommandWithModelCallingSaveIsRejected() {
        RecordingWriter writer = new RecordingWriter();
        MockCapabilityProvider provider = new MockCapabilityProvider(
                new InMemoryMemoryStore(), writer);

        // 用户实际只说"查天气",Detector 未命中任何记忆关键词
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("key", "injected_payload");
        args.put("value", "用户没要求保存这个,模型被诱导了");
        ToolResult result = provider.execute(
                AgentRequest.builder("查天气", Actor.DRIVER)
                        .memorySaveAllowed(false)  // Detector 未命中,Repository 注入 false
                        .build(),
                new ToolCall("memory.semantic.save", args));

        assertEquals("提示注入场景 → POLICY_REJECTED",
                ToolResult.Status.POLICY_REJECTED, result.getStatus());
        assertFalse("writer 不应被调(注入文本不进 memory_record)",
                writer.writeCount.get() > 0);
    }

    @Test
    public void emptyCommandWithModelCallingSaveIsRejected() {
        RecordingWriter writer = new RecordingWriter();
        MockCapabilityProvider provider = new MockCapabilityProvider(
                new InMemoryMemoryStore(), writer);

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("key", "k");
        args.put("value", "v");
        ToolResult result = provider.execute(
                AgentRequest.builder("", Actor.DRIVER)
                        .memorySaveAllowed(false)
                        .build(),
                new ToolCall("memory.semantic.save", args));

        assertEquals(ToolResult.Status.POLICY_REJECTED, result.getStatus());
        assertEquals(0, writer.writeCount.get());
    }

    private static final class RecordingWriter implements MemoryWriter {
        final AtomicInteger writeCount = new AtomicInteger();

        @Override
        public void writeEpisodicOnTerminal(AgentRequest request,
                com.matrix.agent.task.AgentOutcome outcome, long requestEpoch) { }

        @Override
        public boolean writeSemantic(String userId, String zone, String key, String value,
                double score, String sourceSessionId, long requestEpoch) {
            writeCount.incrementAndGet();
            return true;
        }

        @Override
        public String readSemantic(String userId, String zone, String key) {
            return null;
        }
    }
}
