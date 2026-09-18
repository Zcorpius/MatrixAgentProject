package com.matrix.agent.intent;

import com.matrix.agent.intent.LlmIntentClassifier;

import com.matrix.agent.intent.IntentResult;

import com.matrix.agent.intent.IntentClassifier;

import com.matrix.agent.identity.CancellationToken;

import com.matrix.agent.identity.AgentRequest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.matrix.agent.contract.ApiProtocol;
import com.matrix.agent.contract.LlmClient;
import com.matrix.agent.contract.ModelConfig;
import com.matrix.agent.contract.PlannerMode;

/**
 * LlmIntentClassifier 用 3s 短 deadline 透传给 LlmClient.complete。
 *
 * <p>用 fake LlmClient 捕获 deadline 参数,验证:
 * <ul>
 *   <li>调用 5 参重载(而非旧 3 参);</li>
 *   <li>deadline ≈ now + 3s(允许 ±10s 测试调度抖动);</li>
 *   <li>token 传 null(IntentClassifier 不在 AgentRequest 主路径,无需 cancel 感知)。</li>
 * </ul>
 */
public final class LlmIntentClassifierDeadlineTest {

    @Test
    public void classifyUsesFiveArgOverloadWithThreeSecondDeadline() {
        CapturingClient client = new CapturingClient("READ");
        LlmIntentClassifier classifier = new LlmIntentClassifier(client, newConfig());

        long before = System.currentTimeMillis();
        IntentResult result = classifier.classify("打开空调");
        long after = System.currentTimeMillis();

        assertNotNull(result);
        assertTrue("LLM 返回 READ → readOnly=true", result.isReadOnly());
        assertTrue("5 参重载应被调用", client.fiveArgCalled.get() > 0);
        assertEquals("旧 3 参重载不应被调用", 0, client.threeArgCalled.get());

        long capturedDeadline = client.lastDeadlineAtMillis.get();
        assertTrue("deadline 应 ≥ before+3s-50ms (调度抖动), got=" + capturedDeadline
                + " before=" + before,
                capturedDeadline >= before + LlmIntentClassifier.DEADLINE_MS - 50L);
        assertTrue("deadline 应 ≤ after+3s (调度抖动), got=" + capturedDeadline
                + " after=" + after,
                capturedDeadline <= after + LlmIntentClassifier.DEADLINE_MS);
        assertEquals("token 应为 null(IntentClassifier 不感知 cancel)",
                /* expected */ null, client.lastToken.get());
    }

    private static ModelConfig newConfig() {
        return new ModelConfig("test", "Test", ApiProtocol.OPENAI_CHAT,
                "http://example.com/v1", "test-model", "test-key", false,
                PlannerMode.STRUCTURED_JSON_COMPATIBILITY);
    }

    private static final class CapturingClient implements LlmClient {
        final AtomicLong threeArgCalled = new AtomicLong(0);
        final AtomicLong fiveArgCalled = new AtomicLong(0);
        final AtomicLong lastDeadlineAtMillis = new AtomicLong(Long.MIN_VALUE);
        final AtomicReference<CancellationToken> lastToken = new AtomicReference<>(null);
        final String reply;

        CapturingClient(String reply) {
            this.reply = reply;
        }

        @Override
        public String complete(ModelConfig config, String systemPrompt, String userPrompt) {
            threeArgCalled.incrementAndGet();
            return reply;
        }

        @Override
        public String complete(ModelConfig config, String systemPrompt, String userPrompt,
                CancellationToken token, long deadlineAtMillis) {
            fiveArgCalled.incrementAndGet();
            lastDeadlineAtMillis.set(deadlineAtMillis);
            lastToken.set(token);
            return reply;
        }

    }
}
