package com.matrix.agent.task;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.matrix.agent.task.identity.AgentRequest;
import com.matrix.agent.task.identity.Actor;

/**
 * 80% 主动触发阈值边界测试。
 *
 * <p>AgentEngine.tryCompressConversation 在循环顶部检查 chars > budget.totalInputChars × 0.8,
 * 触发后调用 ConversationCompressor.compress。本测试直接验证 compress 的阈值行为:
 * <ul>
 *   <li>≤ 80% → 原样返回(不压缩);</li>
 *   <li>> 80% → 触发压缩。</li>
 * </ul>
 */
public final class ConversationCompressorEightyPercentTriggerTest {

    /**
     * 50 chars ≤ 80% × 100 = 80 → 不触发,原样返回。
     */
    @Test
    public void underEightyPercentNoCompression() {
        List<AgentMessage> conv = new ArrayList<>();
        conv.add(AgentMessage.user("01234567890123456789012345678901234567890123456789"));  // 50 chars

        RecordingProvider provider = new RecordingProvider("应不被调用");
        ConversationCompressor compressor = new ConversationCompressor(provider);
        AgentBudget budget = new AgentBudget(10, 10, 60_000L, 10_000, 100, 50);

        List<AgentMessage> result = compressor.compress(conv, budget,
                AgentRequest.builder("test", Actor.DRIVER).build());

        assertSame("未触发阈值 → 原样返回同一 list", conv, result);
        assertTrue("Provider 不应被调用", provider.callCount.get() == 0);
    }

    /**
     * 90 chars > 80% × 100 = 80 → 触发压缩。
     */
    @Test
    public void overEightyPercentTriggersCompression() {
        // 构造 ≥ 4 个 transaction,确保 toSummarize 非空
        List<AgentMessage> conv = new ArrayList<>();
        conv.add(AgentMessage.user("告诉我说个笑话"));  // txn1 (toSummarize)
        conv.add(AgentMessage.assistant("程序员最喜欢的树是?", Collections.emptyList()));
        conv.add(AgentMessage.user("什么?"));  // txn2 (recent)
        conv.add(AgentMessage.assistant("二叉树", Collections.emptyList()));
        conv.add(AgentMessage.user("好笑"));  // txn3 (recent)
        conv.add(AgentMessage.assistant("谢谢", Collections.emptyList()));
        conv.add(AgentMessage.user("再来"));  // txn4 (recent)
        conv.add(AgentMessage.assistant("不来了", Collections.emptyList()));

        RecordingProvider provider = new RecordingProvider("压缩后摘要");
        ConversationCompressor compressor = new ConversationCompressor(provider);
        AgentBudget budget = new AgentBudget(10, 10, 60_000L, 10_000, 30, 50);

        List<AgentMessage> result = compressor.compress(conv, budget,
                AgentRequest.builder("test", Actor.DRIVER).build());

        assertTrue("超过 80% 阈值 → 触发压缩 (result != conv)", result != conv);
        assertTrue("Provider 应被调用", provider.callCount.get() > 0);
    }

    private static final class RecordingProvider implements SummaryProvider {
        final AtomicInteger callCount = new AtomicInteger(0);
        final String reply;

        RecordingProvider(String reply) {
            this.reply = reply;
        }

        @Override
        public String summarize(List<AgentMessage> turns, AgentRequest request) throws Exception {
            callCount.incrementAndGet();
            return reply;
        }
    }
}
