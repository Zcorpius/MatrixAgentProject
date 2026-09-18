package com.matrix.agent.intent;

import com.matrix.agent.intent.LlmIntentClassifier;

import com.matrix.agent.intent.FallbackIntentClassifier;

import com.matrix.agent.intent.KeywordIntentClassifier;

import com.matrix.agent.intent.IntentResult;

import com.matrix.agent.intent.IntentClassifier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

/**
 * IntentResult / KeywordIntentClassifier.classify / FallbackIntentClassifier 测试。
 *
 * <p>LlmIntentClassifier 的 Provider 调用由 androidTest 真实 HTTP 跑;JVM 单测覆盖:
 * <ul>
 *   <li>IntentResult 工厂方法 + isHighConfidence 阈值;</li>
 *   <li>KeywordIntentClassifier.classify 与 isReadOnly 行为一致;</li>
 *   <li>FallbackIntentClassifier:primary 高置信透传 / 低置信退 fallback / 异常退 fallback;</li>
 *   <li>旧 isReadOnly default impl 转发 classify(接口兼容)。</li>
 * </ul>
 */
public final class IntentClassifierStage9Test {

    @Test
    public void intentResultReadOnlyHighConfidence() {
        IntentResult r = IntentResult.readOnly("test");
        assertTrue(r.isReadOnly());
        assertEquals(1.0, r.getConfidence(), 0.0001);
        assertTrue(r.isHighConfidence());
        assertEquals("test", r.getReason());
    }

    @Test
    public void intentResultWriteHighConfidence() {
        IntentResult r = IntentResult.write("test");
        assertFalse(r.isReadOnly());
        assertEquals(1.0, r.getConfidence(), 0.0001);
        assertTrue(r.isHighConfidence());
    }

    @Test
    public void intentResultUnknownLowConfidence() {
        IntentResult r = IntentResult.unknown("unclear", 0.5);
        // unknown → 按 write 保守处理(readOnly=false),confidence<阈值
        assertFalse(r.isReadOnly());
        assertEquals(0.5, r.getConfidence(), 0.0001);
        assertFalse("confidence < 0.7 阈值 → 低置信", r.isHighConfidence());
    }

    @Test
    public void intentResultUnknownClampsConfidence() {
        // unknown 不允许 confidence ≥ 0.7(否则就不是 unknown 了)
        IntentResult r = IntentResult.unknown("test", 0.95);
        assertTrue("clamp 到 0.69", r.getConfidence() < 0.7);
        assertFalse(r.isHighConfidence());
    }

    @Test
    public void keywordClassifyReadCommand() {
        KeywordIntentClassifier k = KeywordIntentClassifier.INSTANCE;
        IntentResult r = k.classify("查胎压");

        assertTrue(r.isReadOnly());
        assertTrue(r.isHighConfidence());
        assertTrue(r.getReason().contains("read"));
    }

    @Test
    public void keywordClassifyWriteCommand() {
        KeywordIntentClassifier k = KeywordIntentClassifier.INSTANCE;
        IntentResult r = k.classify("打开空调");

        assertFalse(r.isReadOnly());
        assertTrue(r.isHighConfidence());
        assertTrue(r.getReason().contains("write"));
    }

    @Test
    public void keywordClassifyUnknownCommand() {
        KeywordIntentClassifier k = KeywordIntentClassifier.INSTANCE;
        IntentResult r = k.classify("周杰伦");

        assertFalse(r.isReadOnly());
        assertFalse(r.isHighConfidence());
        assertTrue(r.getReason().contains("no-keyword"));
    }

    @Test
    public void keywordClassifyMixedKeywords() {
        KeywordIntentClassifier k = KeywordIntentClassifier.INSTANCE;
        // "查" 是 read,"导航" 是 write → mixed
        IntentResult r = k.classify("查导航历史");

        assertFalse(r.isReadOnly());
        assertFalse("mixed → 低置信", r.isHighConfidence());
        assertTrue(r.getReason().contains("mixed"));
    }

    @Test
    public void keywordClassifyEmptyCommand() {
        KeywordIntentClassifier k = KeywordIntentClassifier.INSTANCE;
        IntentResult r = k.classify("");

        assertFalse(r.isReadOnly());
        assertFalse(r.isHighConfidence());
        assertEquals("empty-command", r.getReason());
    }

    @Test
    public void isReadOnlyDefaultImplForwardsToClassify() {
        // 验证 IntentClassifier 接口的 default isReadOnly 转发 classify
        IntentClassifier k = KeywordIntentClassifier.INSTANCE;
        assertTrue(k.isReadOnly("查胎压"));
        assertFalse(k.isReadOnly("打开空调"));
        assertFalse(k.isReadOnly("周杰伦"));
    }

    @Test
    public void fallbackUsesPrimaryWhenHighConfidence() {
        RecordingClassifier primary = new RecordingClassifier(IntentResult.readOnly("llm-read"));
        RecordingClassifier fallback = new RecordingClassifier(IntentResult.write("kw"));
        FallbackIntentClassifier fc = new FallbackIntentClassifier(primary, fallback);

        IntentResult r = fc.classify("查胎压");

        assertTrue(r.isReadOnly());
        assertTrue("primary 透传", r.getReason().contains("llm-read"));
        assertEquals("primary 调用 1 次", 1, primary.callCount.get());
        assertEquals("fallback 未调用", 0, fallback.callCount.get());
    }

    @Test
    public void fallbackUsesFallbackWhenPrimaryLowConfidence() {
        RecordingClassifier primary = new RecordingClassifier(IntentResult.unknown("unclear", 0.3));
        RecordingClassifier fallback = new RecordingClassifier(IntentResult.write("kw-write"));
        FallbackIntentClassifier fc = new FallbackIntentClassifier(primary, fallback);

        IntentResult r = fc.classify("查胎压");

        assertFalse(r.isReadOnly());
        assertTrue("reason 含 fallback 标记", r.getReason().contains("primary-low-confidence"));
        assertTrue("reason 含 fallback 原始 reason", r.getReason().contains("kw-write"));
        assertEquals("primary 调用 1 次", 1, primary.callCount.get());
        assertEquals("fallback 调用 1 次", 1, fallback.callCount.get());
    }

    @Test
    public void fallbackUsesFallbackWhenPrimaryThrows() {
        ThrowingClassifier primary = new ThrowingClassifier();
        RecordingClassifier fallback = new RecordingClassifier(IntentResult.write("kw-write"));
        FallbackIntentClassifier fc = new FallbackIntentClassifier(primary, fallback);

        IntentResult r = fc.classify("查胎压");

        assertFalse(r.isReadOnly());
        assertTrue("reason 含 primary-failed", r.getReason().contains("primary-failed"));
        assertEquals("fallback 调用 1 次", 1, fallback.callCount.get());
    }

    @Test
    public void fallbackEmptyCommandReturnsUnknownWithoutCallingPrimary() {
        RecordingClassifier primary = new RecordingClassifier(IntentResult.readOnly("llm"));
        RecordingClassifier fallback = new RecordingClassifier(IntentResult.write("kw"));
        FallbackIntentClassifier fc = new FallbackIntentClassifier(primary, fallback);

        IntentResult r = fc.classify("");

        assertFalse(r.isReadOnly());
        assertEquals("empty-command", r.getReason());
        assertEquals("primary 未被调用", 0, primary.callCount.get());
        assertEquals("fallback 未被调用", 0, fallback.callCount.get());
    }

    @Test
    public void llmParseResponseParsesRead() {
        IntentResult r = LlmIntentClassifier.parseLlmResponse("READ");
        assertTrue(r.isReadOnly());
        assertTrue(r.isHighConfidence());
    }

    @Test
    public void llmParseResponseParsesWrite() {
        IntentResult r = LlmIntentClassifier.parseLlmResponse("WRITE");
        assertFalse(r.isReadOnly());
        assertTrue(r.isHighConfidence());
    }

    @Test
    public void llmParseResponseParsesUnknownOrUnparseable() {
        IntentResult r1 = LlmIntentClassifier.parseLlmResponse("UNKNOWN");
        assertFalse(r1.isReadOnly());
        assertFalse(r1.isHighConfidence());

        IntentResult r2 = LlmIntentClassifier.parseLlmResponse("随便乱七八糟的回复");
        assertFalse(r2.isHighConfidence());
    }

    private static final class RecordingClassifier implements IntentClassifier {
        final AtomicInteger callCount = new AtomicInteger(0);
        final IntentResult result;

        RecordingClassifier(IntentResult result) {
            this.result = result;
        }

        @Override
        public IntentResult classify(String command) {
            callCount.incrementAndGet();
            return result;
        }
    }

    private static final class ThrowingClassifier implements IntentClassifier {
        @Override
        public IntentResult classify(String command) {
            throw new LlmIntentClassifier.LlmClassificationException(
                    new RuntimeException("simulated provider timeout"));
        }
    }
}
