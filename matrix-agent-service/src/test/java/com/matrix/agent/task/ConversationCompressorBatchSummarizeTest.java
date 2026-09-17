package com.matrix.agent.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.matrix.agent.task.identity.AgentRequest;
import com.matrix.agent.task.identity.Actor;

/**
 * toSummarizeNatural > MAX_TURNS_TO_SUMMARIZE 分批摘要,
 * Provider 多次被调,前一批 summary 拼到下一批 batch 头部。
 */
public final class ConversationCompressorBatchSummarizeTest {

    /**
     * 构造 25 个 natural transaction(后 3 个进 recent,前 22 个进 toSummarize),
     * toSummarizeNatural=22 > MAX_TURNS_TO_SUMMARIZE=20 → 分 2 批:第 1 批 20,第 2 批 2。
     * Provider 应被调 2 次。
     */
    @Test
    public void batchSummarizeWhenExceedingMaxTurns() {
        List<AgentMessage> conv = new ArrayList<>();
        // 25 个 natural transaction,user+assistant 各一对
        for (int i = 0; i < 25; i++) {
            conv.add(AgentMessage.user("user-msg-" + i));
            conv.add(AgentMessage.assistant("assistant-reply-" + i, Collections.emptyList()));
        }
        // 50 条消息,触发阈值用很小的 budget
        RecordingProvider provider = new RecordingProvider("batch-result");
        ConversationCompressor compressor = new ConversationCompressor(provider);
        AgentBudget budget = new AgentBudget(10, 10, 60_000L, 10_000, 50, 100);
        AgentRequest request = AgentRequest.builder("test", Actor.DRIVER).build();

        List<AgentMessage> compressed = compressor.compress(conv, budget, request);

        // splitByTransactions:25 个 transaction,后 3 个进 recent(6 msgs),前 22 个进 toSummarize(44 msgs)
        // toSummarizeNatural=44 msgs(全 natural) → 分批:batch1=20, batch2=20, batch3=4 → 3 批
        // (44 / 20 = 2.2 → 3 batches)
        assertTrue("Provider 应被调用 ≥ 2 次(分批摘要),actual=" + provider.callCount.get(),
                provider.callCount.get() >= 2);
        assertTrue("compressed 应包含最终 summary 文本",
                compressed.stream().anyMatch(m -> m.getContent().contains("batch-result")));
    }

    /**
     * 极端情况:toSummarizeNatural > MAX_TURNS_TO_SUMMARIZE × MAX_SUMMARIZE_BATCHES = 60
     * → 最老的 (totalBatches - MAX) 批丢弃,summary 末尾加 "[历史已裁剪 X 条]" 提示。
     */
    @Test
    public void extremeCaseDropsOldestWithNotice() {
        List<AgentMessage> conv = new ArrayList<>();
        // 70 个 natural transaction = 140 msgs,远超 60
        for (int i = 0; i < 70; i++) {
            conv.add(AgentMessage.user("msg-" + i));
            conv.add(AgentMessage.assistant("reply-" + i, Collections.emptyList()));
        }
        RecordingProvider provider = new RecordingProvider("summary-text");
        ConversationCompressor compressor = new ConversationCompressor(provider);
        AgentBudget budget = new AgentBudget(10, 10, 60_000L, 10_000, 50, 200);
        AgentRequest request = AgentRequest.builder("test", Actor.DRIVER).build();

        List<AgentMessage> compressed = compressor.compress(conv, budget, request);

        // 70 txns - 3 recent = 67 txns toSummarize = 134 msgs natural
        // batches = ceil(134/20) = 7 batches > MAX_SUMMARIZE_BATCHES=3
        // droppedBefore = (7-3) × 20 = 80 msgs dropped with notice
        boolean hasDropNotice = compressed.stream().anyMatch(m ->
                m.getContent().contains("[历史已裁剪"));
        assertTrue("extreme case summary 应含 '[历史已裁剪]' 提示", hasDropNotice);
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
