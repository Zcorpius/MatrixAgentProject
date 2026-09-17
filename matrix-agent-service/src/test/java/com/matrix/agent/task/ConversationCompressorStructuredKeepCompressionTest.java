package com.matrix.agent.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.matrix.agent.task.identity.AgentRequest;
import com.matrix.agent.task.identity.Actor;
import com.matrix.agent.task.tool.ToolCall;

/**
 * 端到端 + 分批摘要 + heuristic 降级测试。
 *
 * <p>用 fake SummaryProvider 验证 compress 行为:
 * <ul>
 *   <li>structured transactions 不进 Provider.summarize,原样保留到 compressed 列表;</li>
 *   <li>request.remainingMillis < 2s 直接 heuristic,Provider 不被调;</li>
 *   <li>toSummarizeNatural > MAX_TURNS_TO_SUMMARIZE 分批,保留全历史。</li>
 * </ul>
 */
public final class ConversationCompressorStructuredKeepCompressionTest {

    /**
     * compress 后 compressed 列表包含 [summary, ...structuredKeep, ...recent],
     * structuredKeep 原样保留(含 tool_call + observation)。
     */
    @Test
    public void compressKeepsStructuredTransactionsIntact() {
        // 构造 conversation:用户多次问 + 调工具,触发 80% 阈值
        ToolCall call = ToolCall.withId("s1", "vehicle.get_battery", Collections.<String, Object>emptyMap());
        List<AgentMessage> conv = new ArrayList<>();
        // 老的 tool transaction(应进 structuredKeep)
        conv.add(AgentMessage.user("查电量"));  // txn1 start
        conv.add(AgentMessage.assistant("调用 get_battery", Collections.singletonList(call)));  // txn2
        conv.add(AgentMessage.tool("s1", "vehicle.get_battery", "85%"));  // txn2 cont
        // 老的 natural transaction
        conv.add(AgentMessage.user("再告诉我平均车速"));  // txn3
        conv.add(AgentMessage.assistant("平均车速 60 km/h", Collections.emptyList()));  // txn3 cont
        // recent transactions
        conv.add(AgentMessage.user("打开空调"));  // txn4 (recent)
        conv.add(AgentMessage.assistant("好的", Collections.emptyList()));  // txn4 cont

        RecordingProvider provider = new RecordingProvider("摘要文本");
        ConversationCompressor compressor = new ConversationCompressor(provider);
        AgentBudget budget = new AgentBudget(10, 10, 60_000L, 10_000, 30, 50);
        AgentRequest request = newRequest();

        List<AgentMessage> compressed = compressor.compress(conv, budget, request);

        assertTrue("compressed 应包含 summary", containsSummary(compressed));
        assertTrue("compressed 应保留 tool_call assistant",
                containsAssistantWithToolCall(compressed, "vehicle.get_battery"));
        assertTrue("compressed 应保留 tool observation",
                containsToolMessage(compressed, "vehicle.get_battery", "85%"));
        assertEquals("Provider.summarize 至少调用 1 次", 1, provider.callCount.get());
    }

    /**
     * request.remainingMillis < 2s → 直接 heuristic,Provider 不被调。
     */
    @Test
    public void compressFallsBackHeuristicWhenRemainingBudgetBelow2s() {
        List<AgentMessage> conv = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            conv.add(AgentMessage.user("hello " + i));
        }

        RecordingProvider provider = new RecordingProvider("应该不被调用");
        ConversationCompressor compressor = new ConversationCompressor(provider);
        AgentBudget budget = new AgentBudget(10, 10, 60_000L, 10_000, 30, 50);
        // request.timeoutMillis=1s → remaining < 2s → heuristic 降级
        AgentRequest request = AgentRequest.builder("test", Actor.DRIVER)
                .timeoutMillis(1_000L)
                .build();

        List<AgentMessage> compressed = compressor.compress(conv, budget, request);

        assertEquals("Provider 不应被调", 0, provider.callCount.get());
        assertTrue("仍要返回 summary",
                containsSummary(compressed));
    }

    /**
     * 单 batch 路径:toSummarizeNatural 在 MAX_TURNS_TO_SUMMARIZE 内 → 1 次 Provider.summarize。
     * 需构造 ≥ 4 个 transaction(后 3 个进 recent,其余进 toSummarize)。
     */
    @Test
    public void compressSummarizeSingleBatchUnderLimit() {
        List<AgentMessage> conv = new ArrayList<>();
        // 4 个 natural transaction — 前 1 个进 toSummarize,后 3 个进 recent
        conv.add(AgentMessage.user("告诉我说个笑话"));  // txn1 (toSummarize)
        conv.add(AgentMessage.assistant("程序员最喜欢的树是?", Collections.emptyList()));
        conv.add(AgentMessage.user("什么?"));  // txn2 (recent)
        conv.add(AgentMessage.assistant("二叉树", Collections.emptyList()));
        conv.add(AgentMessage.user("好笑"));  // txn3 (recent)
        conv.add(AgentMessage.assistant("谢谢", Collections.emptyList()));
        conv.add(AgentMessage.user("再来一个"));  // txn4 (recent)
        conv.add(AgentMessage.assistant("不来了", Collections.emptyList()));

        RecordingProvider provider = new RecordingProvider("笑话已说");
        ConversationCompressor compressor = new ConversationCompressor(provider);
        AgentBudget budget = new AgentBudget(10, 10, 60_000L, 10_000, 30, 50);
        AgentRequest request = newRequest();

        List<AgentMessage> compressed = compressor.compress(conv, budget, request);

        assertTrue("压缩应触发(compressed != conv)",
                compressed != conv);
        assertEquals("1 batch → 1 次 Provider.summarize", 1, provider.callCount.get());
        assertTrue("summary 包含批次输出",
                compressed.stream().anyMatch(m -> m.getContent().contains("笑话已说")));
    }

    /**
     * heuristic 降级文本含"已省略" + "更早上下文",不静默。
     */
    @Test
    public void heuristicFallbackNotSilent() {
        List<AgentMessage> conv = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            conv.add(AgentMessage.user("hello " + i));
        }
        // provider=null → heuristic
        ConversationCompressor compressor = new ConversationCompressor(null);
        AgentBudget budget = new AgentBudget(10, 10, 60_000L, 10_000, 30, 50);
        AgentRequest request = newRequest();

        List<AgentMessage> compressed = compressor.compress(conv, budget, request);

        boolean hasSummaryWithNotice = compressed.stream().anyMatch(m ->
                m.getContent().contains("上文已省略") && m.getContent().contains("更早上下文"));
        assertTrue("heuristic 文本应含 '上文已省略' + '更早上下文'", hasSummaryWithNotice);
    }

    private static boolean containsSummary(List<AgentMessage> list) {
        return list.stream().anyMatch(m -> m.getRole() == AgentMessage.Role.SYSTEM
                && m.getContent() != null && !m.getContent().isEmpty());
    }

    private static boolean containsAssistantWithToolCall(List<AgentMessage> list, String capName) {
        return list.stream().anyMatch(m -> m.getRole() == AgentMessage.Role.ASSISTANT
                && m.getToolCalls() != null
                && m.getToolCalls().stream().anyMatch(c -> c.getCapabilityName().equals(capName)));
    }

    private static boolean containsToolMessage(List<AgentMessage> list, String toolName, String contentFragment) {
        return list.stream().anyMatch(m -> m.getRole() == AgentMessage.Role.TOOL
                && toolName.equals(m.getToolName())
                && m.getContent() != null && m.getContent().contains(contentFragment));
    }

    private static AgentRequest newRequest() {
        return AgentRequest.builder("test", Actor.DRIVER).build();
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
