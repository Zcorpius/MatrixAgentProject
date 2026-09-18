package com.matrix.agent.task;
import com.matrix.agent.task.compress.*;

import com.matrix.agent.contract.SummaryProvider;

import com.matrix.agent.contract.AgentMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.identity.InputSource;
import com.matrix.agent.identity.VehicleZone;

/**
 * ConversationCompressor 测试——触发阈值 / heuristic 降级 / LLM 摘要路径 / injection 前缀。
 */
public final class ConversationCompressorTest {

    @Test
    public void noCompressionWhenUnderThreshold() {
        // budget.totalInputChars=32000,trigger=0.8×32000=25600;conversation 100 chars 不触发
        AgentBudget budget = new AgentBudget();
        ConversationCompressor compressor = new ConversationCompressor(null);
        List<AgentMessage> conv = Arrays.asList(
                AgentMessage.user("短对话1"),
                AgentMessage.assistant("回复1", Collections.emptyList()));

        List<AgentMessage> result = compressor.compress(conv, budget, newRequest());

        assertEquals("未触发压缩,原 list 返回", conv, result);
    }

    @Test
    public void heuristicFallbackWhenProviderNull() {
        // budget.totalInputChars=400,trigger=320;conversation 600 chars 触发
        AgentBudget budget = new AgentBudget(8, 8, 60_000L, 200, 400, 64);
        ConversationCompressor compressor = new ConversationCompressor(null);
        List<AgentMessage> conv = new ArrayList<>();
        // 8 条 messages,每条 ~100 chars → total 800 chars > trigger 320
        for (int i = 0; i < 8; i++) {
            conv.add(AgentMessage.user("对话内容编号 " + i + ": " + repeat("X", 80)));
        }

        List<AgentMessage> result = compressor.compress(conv, budget, newRequest());

        assertTrue("压缩后条数少于原", result.size() < conv.size());
        assertEquals("首条是 SummaryMessage", AgentMessage.Role.SYSTEM, result.get(0).getRole());
        assertTrue("首条含前缀", result.get(0).getContent().startsWith(AgentMessage.PREFIX_SUMMARY));
        assertTrue("heuristic 文本含 '上文已省略'",
                result.get(0).getContent().contains("上文已省略"));
    }

    @Test
    public void llmProviderSummarizeUsedWhenProvided() {
        AgentBudget budget = new AgentBudget(8, 8, 60_000L, 200, 400, 64);
        RecordingProvider provider = new RecordingProvider("用户希望调空调到24度,已完成。");
        ConversationCompressor compressor = new ConversationCompressor(provider);
        List<AgentMessage> conv = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            conv.add(AgentMessage.user("对话内容 " + i + ": " + repeat("X", 80)));
        }

        List<AgentMessage> result = compressor.compress(conv, budget, newRequest());

        assertEquals("provider 被调用 1 次", 1, provider.callCount.get());
        assertEquals("SummaryMessage 含 provider 返回的摘要文本",
                "用户希望调空调到24度,已完成。",
                result.get(0).getContent().substring(AgentMessage.PREFIX_SUMMARY.length()));
        assertTrue("首条含前缀(注入防御)",
                result.get(0).getContent().startsWith(AgentMessage.PREFIX_SUMMARY));
    }

    @Test
    public void llmProviderFailureFallsBackToHeuristic() {
        AgentBudget budget = new AgentBudget(8, 8, 60_000L, 200, 400, 64);
        ThrowingProvider provider = new ThrowingProvider();
        ConversationCompressor compressor = new ConversationCompressor(provider);
        List<AgentMessage> conv = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            conv.add(AgentMessage.user("对话内容 " + i + ": " + repeat("X", 80)));
        }

        List<AgentMessage> result = compressor.compress(conv, budget, newRequest());

        assertEquals("provider 被调用(但失败)", 1, provider.callCount.get());
        assertTrue("heuristic 文本含 '上文已省略'",
                result.get(0).getContent().contains("上文已省略"));
    }

    @Test
    public void emptyProviderResponseFallsBackToHeuristic() {
        AgentBudget budget = new AgentBudget(8, 8, 60_000L, 200, 400, 64);
        RecordingProvider provider = new RecordingProvider("");  // 空字符串
        ConversationCompressor compressor = new ConversationCompressor(provider);
        List<AgentMessage> conv = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            conv.add(AgentMessage.user("对话内容 " + i + ": " + repeat("X", 80)));
        }

        List<AgentMessage> result = compressor.compress(conv, budget, newRequest());

        assertTrue("空 provider 返回 → heuristic 降级",
                result.get(0).getContent().contains("上文已省略"));
    }

    @Test
    public void recentTurnsPreservedAfterCompression() {
        AgentBudget budget = new AgentBudget(8, 8, 60_000L, 200, 400, 64);
        ConversationCompressor compressor = new ConversationCompressor(null);
        List<AgentMessage> conv = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            conv.add(AgentMessage.user("old " + i + ": " + repeat("X", 60)));
        }
        // 最后 2 条是 recent
        AgentMessage last1 = AgentMessage.user("最新 user 内容");
        AgentMessage last2 = AgentMessage.assistant("最新 assistant 回复", Collections.emptyList());
        conv.add(last1);
        conv.add(last2);

        List<AgentMessage> result = compressor.compress(conv, budget, newRequest());

        // result = [summary, ...recent];recent 必须包含 last1, last2 在末尾
        assertEquals("末尾应该是 last2", last2, result.get(result.size() - 1));
        assertEquals("倒数第 2 应该是 last1", last1, result.get(result.size() - 2));
    }

    @Test
    public void preservesAtLeastHeuristicKeepRecent() {
        // 仅 6 条 turns——HEURISTIC_KEEP_RECENT=4,要保留至少 4 条 recent
        AgentBudget budget = new AgentBudget(8, 8, 60_000L, 100, 200, 64);
        ConversationCompressor compressor = new ConversationCompressor(null);
        List<AgentMessage> conv = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            conv.add(AgentMessage.user("对话内容 " + i + ": " + repeat("X", 80)));
        }

        List<AgentMessage> result = compressor.compress(conv, budget, newRequest());

        // recent ≥ 4 条 + 1 summary = ≥ 5
        assertTrue("recent ≥ HEURISTIC_KEEP_RECENT=4,总条数 ≥ 5",
                result.size() >= 5);
    }

    @Test
    public void summaryMessageHasSystemRoleAndPrefix() {
        AgentBudget budget = new AgentBudget(8, 8, 60_000L, 200, 400, 64);
        ConversationCompressor compressor = new ConversationCompressor(null);
        List<AgentMessage> conv = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            conv.add(AgentMessage.user("对话内容 " + i + ": " + repeat("X", 80)));
        }

        List<AgentMessage> result = compressor.compress(conv, budget, newRequest());

        AgentMessage summary = result.get(0);
        assertEquals(AgentMessage.Role.SYSTEM, summary.getRole());
        assertTrue("必须含强前缀(注入防御)",
                summary.getContent().contains("[系统生成的对话摘要"));
        assertNotNull(summary.getContent());
    }

    @Test
    public void emptyOrNullConversationReturnsAsIs() {
        AgentBudget budget = new AgentBudget();
        ConversationCompressor compressor = new ConversationCompressor(null);

        List<AgentMessage> empty = Collections.emptyList();
        assertEquals(empty, compressor.compress(empty, budget, newRequest()));
        assertEquals(null, compressor.compress(null, budget, newRequest()));
    }

    private static AgentRequest newRequest() {
        return AgentRequest.builder("压缩测试", Actor.DRIVER)
                .occupantZone(VehicleZone.DRIVER)
                .inputSource(InputSource.TOUCH)
                .sessionId("compress-test")
                .build();
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder(n * s.length());
        for (int i = 0; i < n; i++) sb.append(s);
        return sb.toString();
    }

    private static final class RecordingProvider implements SummaryProvider {
        final AtomicInteger callCount = new AtomicInteger(0);
        final String response;

        RecordingProvider(String response) {
            this.response = response;
        }

        @Override
        public String summarize(List<AgentMessage> turns, AgentRequest request) {
            callCount.incrementAndGet();
            return response;
        }
    }

    private static final class ThrowingProvider implements SummaryProvider {
        final AtomicInteger callCount = new AtomicInteger(0);

        @Override
        public String summarize(List<AgentMessage> turns, AgentRequest request) {
            callCount.incrementAndGet();
            throw new RuntimeException("simulated provider failure");
        }
    }
}
