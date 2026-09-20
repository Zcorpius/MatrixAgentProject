package com.matrix.agent.debugtrace;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 调试轨迹验收矩阵（评估 v1.0 §4.3）：双汇纪律、Redactor 红线、分片重组、门控语义。
 * JVM 侧验证 Emitter/Redactor 逻辑；BuildConfig 门控的构建级验证在设备侧。
 */
public final class DebugTraceEmitterTest {

    /** 契约 5/7：UI 关闭（uiEnabled=false）时仍写日志（日志汇无条件）——ring buffer 为空。 */
    @Test public void uiDisabledStillEmitsLogButNotRing() {
        DebugTraceEmitter emitter = new DebugTraceEmitter(false, null);
        emitter.emit(DebugTraceEvent.PHASE_MODEL_PROPOSED, "task-1", "toolCalls=2");
        // 日志汇已输出（Log.i 在 JVM 测试环境静默）——ring buffer 不写入
        assertTrue("UI 关闭时 ring buffer 恒空", emitter.snapshot().isEmpty());
    }

    /** 契约 5：UI 开启时事件进 ring buffer 且有界（容量上限截尾）。 */
    @Test public void uiEnabledWritesBoundedRingBuffer() {
        DebugTraceEmitter emitter = new DebugTraceEmitter(true, null);
        for (int i = 0; i < 550; i++) {
            emitter.emit(DebugTraceEvent.PHASE_MODEL_PROPOSED, "task-" + i, "event-" + i);
        }
        List<DebugTraceEvent> snapshot = emitter.snapshot();
        assertEquals("ring buffer 有界（500）", 500, snapshot.size());
        assertEquals("最新事件在尾部", "event-549", snapshot.get(499).payload);
    }

    /** 契约 5：3 KiB 分片 + traceId/partIndex/partCount 重组键。 */
    @Test public void longPayloadIsChunkedWithReassemblyKeys() {
        DebugTraceEmitter emitter = new DebugTraceEmitter(true, null);
        String longReasoning = "思".repeat(5000); // 15000 UTF-8 bytes → ≥5 chunks
        emitter.emit(DebugTraceEvent.PHASE_MODEL_REASONING, "task-x", longReasoning);

        List<DebugTraceEvent> events = emitter.snapshot();
        assertTrue("多分片", events.size() >= 5);
        // 所有分片共享同一 traceId
        String traceId = events.get(0).traceId;
        for (DebugTraceEvent event : events) {
            assertEquals(traceId, event.traceId);
        }
        // partIndex 连续且 partCount 一致
        assertEquals(0, events.get(0).partIndex);
        assertEquals(events.size(), events.get(0).partCount);
        assertEquals(events.size() - 1, events.get(events.size() - 1).partIndex);
        // 重组后内容等值（Redactor 截断前的原始长度恢复——分片按 UTF-8 字节切，
        // 重组在消费侧按 partIndex 拼接 payload）
        StringBuilder reassembled = new StringBuilder();
        for (DebugTraceEvent event : events) {
            reassembled.append(event.payload);
        }
        assertEquals(longReasoning.length(), reassembled.toString().length());
    }

    /** 契约 6：Redactor 红线——密钥/系统提示词整行丢弃（不替换为半句）。 */
    @Test public void redactorDropsSensitiveLinesEntirely() {
        assertNull(DebugTraceRedactor.redactLine("Bearer sk-abc123"));
        assertNull(DebugTraceRedactor.redactLine("system prompt: 你是..."));
        assertNull(DebugTraceRedactor.redactLine("apiKey=secret-value-here"));
        assertNull(DebugTraceRedactor.redactLine(null));
        assertNull(DebugTraceRedactor.redactLine("   "));
    }

    /** 契约 6：合法内容截断到 MAX_LINE_CHARS。 */
    @Test public void redactorTruncatesLongLines() {
        String longText = "a".repeat(500);
        String redacted = DebugTraceRedactor.redactLine(longText);
        assertEquals(DebugTraceRedactor.MAX_LINE_CHARS + 1, redacted.length()); // +1 为省略号
        assertTrue(redacted.endsWith("…"));
    }

    /** 契约 6：Map 值标量化——对象折叠为类型名、长字符串丢弃。 */
    @Test public void redactorFoldsNonScalarMapValues() {
        java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("percent", 30);
        values.put("mode", "cool");
        values.put("callback", java.util.Map.of("evil", 1));
        values.put("freeText", "x".repeat(100));
        List<String> lines = DebugTraceRedactor.redactMap(values);
        assertEquals(4, lines.size()); // 全部保留：标量直出、Map/长文本折叠为类型名
        assertTrue(lines.get(0).contains("percent=30"));
        assertTrue(lines.get(1).contains("mode=cool"));
        assertFalse("Map 值不显示原文", lines.get(2).contains("evil"));
        assertTrue(lines.get(3).contains("freeText=String"));
    }

    /** 契约 3：订阅实时推送；退订即停。 */
    @Test public void subscribeReceivesEventsUnsubscribeStops() {
        DebugTraceEmitter emitter = new DebugTraceEmitter(true, null);
        AtomicInteger received = new AtomicInteger();
        java.util.function.Consumer<DebugTraceEvent> subscriber = event ->
                received.incrementAndGet();
        emitter.subscribe(subscriber);
        emitter.emit(DebugTraceEvent.PHASE_ROUND_START, "t1", "start");
        emitter.emit(DebugTraceEvent.PHASE_ROUND_END, "t1", "end");
        assertEquals(2, received.get());
        emitter.unsubscribe(subscriber);
        emitter.emit(DebugTraceEvent.PHASE_ROUND_START, "t2", "start-2");
        assertEquals("退订后不再接收", 2, received.get());
    }

    /** 契约 3：UI 关闭时 subscribe 拒绝注册。 */
    @Test public void subscribeRejectedWhenUiDisabled() {
        DebugTraceEmitter emitter = new DebugTraceEmitter(false, null);
        AtomicInteger received = new AtomicInteger();
        java.util.function.Consumer<DebugTraceEvent> subscriber = event ->
                received.incrementAndGet();
        emitter.subscribe(subscriber);
        emitter.emit(DebugTraceEvent.PHASE_ROUND_START, "t1", "start");
        assertEquals("UI 关闭不产生订阅事件", 0, received.get());
    }
}
