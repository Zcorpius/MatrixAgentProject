package com.matrix.agent.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

import com.matrix.agent.task.identity.AgentRequest;
import com.matrix.agent.task.identity.Actor;
import com.matrix.agent.task.identity.CancellationToken;
import com.matrix.agent.model.ApiProtocol;
import com.matrix.agent.model.LlmClient;
import com.matrix.agent.model.ModelConfig;
import com.matrix.agent.model.PlannerMode;

/**
 * LlmSummaryProvider 用 LlmClient 5 参重载,
 * 透传 AgentRequest token + min(10s, request.remainingMillis()) deadline。
 *
 * <p>覆盖:
 * <ul>
 *   <li>5 参重载被调用(非旧 3 参);token 透传正确;</li>
 *   <li>deadline = min(now + 10s, request.deadlineAtMillis);</li>
 *   <li>request.remainingMillis < 2s → 抛 SummaryUnavailableException,client 不被调。</li>
 * </ul>
 */
public final class LlmSummaryProviderCancelDeadlineTest {

    /**
     * 5 参重载被调用,token 透传,deadline = min(now+10s, request.deadline)。
     */
    @Test
    public void summarizeUsesFiveArgOverloadWithTokenAndMinDeadline() throws Exception {
        CapturingClient client = new CapturingClient("摘要内容");
        LlmSummaryProvider provider = new LlmSummaryProvider(client, newConfig());

        CancellationToken token = new CancellationToken();
        // request.timeout=30s → deadline ≈ now+30s → min(10s, 30s) = 10s
        long before = System.currentTimeMillis();
        AgentRequest request = AgentRequest.builder("test", Actor.DRIVER)
                .cancellationToken(token)
                .timeoutMillis(30_000L)
                .build();
        long after = System.currentTimeMillis();

        String result = provider.summarize(
                Collections.singletonList(AgentMessage.user("hello")), request);

        assertEquals("摘要文本", "摘要内容", result);
        assertEquals("旧 3 参重载不应被调用", 0, client.threeArgCalled.get());
        assertEquals("5 参重载应被调用 1 次", 1, client.fiveArgCalled.get());
        assertTrue("token 应透传", client.lastToken.get() == token);

        long capturedDeadline = client.lastDeadlineAtMillis.get();
        // deadline = min(now + 10s, requestDeadline) = now+10s(requestDeadline=now+30s)
        assertTrue("captured deadline ≈ before+10s, got=" + capturedDeadline,
                capturedDeadline >= before + LlmSummaryProvider.PROVIDER_DEADLINE_MS - 200L
                        && capturedDeadline <= after + LlmSummaryProvider.PROVIDER_DEADLINE_MS + 200L);
    }

    /**
     * request.timeout < 10s → min(10s, request.deadline) = request.deadline。
     */
    @Test
    public void summarizeDeadlineCappedByRequestDeadline() throws Exception {
        CapturingClient client = new CapturingClient("ok");
        LlmSummaryProvider provider = new LlmSummaryProvider(client, newConfig());

        long before = System.currentTimeMillis();
        AgentRequest request = AgentRequest.builder("test", Actor.DRIVER)
                .timeoutMillis(5_000L)  // < 10s
                .build();

        provider.summarize(Collections.singletonList(AgentMessage.user("x")), request);

        long capturedDeadline = client.lastDeadlineAtMillis.get();
        // deadline 应被 request.deadline 截断到 ~5s(在 [before+4.8s, before+5.2s] 之间)
        assertTrue("deadline 应被截断到 ~5s, got=" + capturedDeadline
                + " before=" + before,
                capturedDeadline >= before + 4_800L && capturedDeadline <= before + 5_200L);
    }

    /**
     * request.remaining < 2s → 抛 SummaryUnavailableException,client 不被调。
     * 用 timeoutMillis=1500ms 构造一个 < 2s 的剩余预算(deadline ≈ now+1.5s)。
     */
    @Test
    public void summarizeFailsWhenRemainingBelowTwoSeconds() {
        CapturingClient client = new CapturingClient("ok");
        LlmSummaryProvider provider = new LlmSummaryProvider(client, newConfig());

        AgentRequest request = AgentRequest.builder("test", Actor.DRIVER)
                .timeoutMillis(1_500L)  // < 2s
                .build();

        try {
            provider.summarize(Collections.singletonList(AgentMessage.user("x")), request);
            fail("remaining < 2s 应抛 SummaryUnavailableException");
        } catch (LlmSummaryProvider.SummaryUnavailableException expected) {
            assertTrue("message 含 remaining",
                    expected.getMessage().contains("remaining"));
            assertEquals("client 不被调", 0, client.fiveArgCalled.get());
        } catch (Exception ex) {
            fail("应抛 SummaryUnavailableException,实际:" + ex.getClass().getSimpleName());
        }
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
        final AtomicReference<CancellationToken> lastToken = new AtomicReference<>();
        final String reply;

        CapturingClient(String reply) {
            this.reply = reply;
        }

        @Override
        public String complete(ModelConfig config, String system, String user) {
            threeArgCalled.incrementAndGet();
            return reply;
        }

        @Override
        public String complete(ModelConfig config, String system, String user,
                CancellationToken token, long deadlineAtMillis) {
            fiveArgCalled.incrementAndGet();
            lastDeadlineAtMillis.set(deadlineAtMillis);
            lastToken.set(token);
            return reply;
        }

    }
}
