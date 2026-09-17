package com.matrix.agent.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import com.matrix.agent.task.identity.Actor;
import com.matrix.agent.task.identity.AgentRequest;
import com.matrix.agent.task.identity.InputSource;
import com.matrix.agent.task.identity.VehicleZone;
import com.matrix.agent.task.tool.ToolCall;

/**
 * ConversationCompressor transaction-aware 截断契约测试。
 *
 * <p>评审发现旧实现按 {@code conversation.size()/2} 截断,可能把 assistant.tool_calls
 * 与其后续 tool observation 拆开——observation 在 recent 中无对应 assistant 父消息,
 * Provider 协议(Anthropic / OpenAI Tool Calling)会拒绝或乱序。本测试覆盖:
 * <ul>
 *   <li>assistant.tool_calls + observation 不被拆开;</li>
 *   <li>多 tool 调用同 transaction;</li>
 *   <li>POLICY 拒绝 / EXECUTION_UNKNOWN observation 跟随父 assistant;</li>
 *   <li>纯文本轮次按 transaction 切分(每 user 一个 transaction);</li>
 *   <li>transaction 数 &lt; MAX_RECENT_TRANSACTIONS 时 HEURISTIC_KEEP_RECENT 兜底。</li>
 * </ul>
 */
public final class ConversationCompressorTransactionTest {

    @Test
    public void toolCallsAndObservationStayInSameTransaction() {
        // 构造 conversation:
        // [user, assistant.tool_calls(空调), tool.observation, user, assistant.tool_calls(导航),
        //  tool.observation, tool.observation, user, assistant(纯文本)]
        // = 9 条消息,3 个 user transaction + 2 个 tool_call transaction + 1 个 assistant 文本 transaction
        List<AgentMessage> conv = new ArrayList<>();
        conv.add(AgentMessage.user("调空调到24度"));
        conv.add(AgentMessage.assistant(null, Arrays.asList(
                new ToolCall("climate.set_temperature", Collections.<String, Object>singletonMap("temp", 24)))));
        conv.add(AgentMessage.tool("step-1", "climate.set_temperature", "OK 24度"));
        conv.add(AgentMessage.user("导航回家"));
        conv.add(AgentMessage.assistant(null, Arrays.asList(
                new ToolCall("navigation.start", Collections.<String, Object>singletonMap("dest", "redacted")))));
        conv.add(AgentMessage.tool("step-2", "navigation.start", "已开始导航"));
        conv.add(AgentMessage.tool("step-3", "navigation.status", "ETA 30min"));
        conv.add(AgentMessage.user("还有什么"));
        conv.add(AgentMessage.assistant("我能帮你查车况、调空调等", Collections.<ToolCall>emptyList()));

        List<AgentMessage> toSummarize = new ArrayList<>();
        List<AgentMessage> recent = new ArrayList<>();
        ConversationCompressor.splitByTransactions(conv, toSummarize, recent);

        // 期望:recent 至少含 [user(还有什么), assistant(纯文本)] 1 个完整 transaction
        // 加上前一个 transaction [user(导航), assistant.tool_calls(导航), tool.observation x2]
        // = recent ≥ 6 条(2 个 transaction,4+2=6 条消息);toSummarize = 前 3 条(user,assistant.tool_calls,tool.observation)
        assertTrue("recent 必须含最后两条(纯文本轮次)", recent.contains(conv.get(7)));
        assertTrue(recent.contains(conv.get(8)));
        assertTrue("navigation.tool_calls 与 observation 必须同 transaction(都在 recent 或都在 summarize)",
                sameGroup(conv, recent, toSummarize, 4, 5, 6));
        assertTrue("climate.tool_calls 与 observation 必须同 transaction",
                sameGroup(conv, recent, toSummarize, 1, 2));
    }

    @Test
    public void toolCallsAndObservationNotSplitByCompression() {
        // 强制触发压缩,验证 result 不含 "孤儿 observation"(无对应 assistant 父消息)
        AgentBudget budget = new AgentBudget(8, 8, 60_000L, 100, 400, 64);
        ConversationCompressor compressor = new ConversationCompressor(null);
        List<AgentMessage> conv = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            conv.add(AgentMessage.user("old " + i + ": " + repeat("X", 60)));
        }
        // 后续 tool transaction
        conv.add(AgentMessage.assistant(null, Arrays.asList(
                new ToolCall("cap.x", Collections.<String, Object>singletonMap("k", "v")))));
        conv.add(AgentMessage.tool("s-1", "cap.x", "result"));
        conv.add(AgentMessage.user("recent"));
        conv.add(AgentMessage.assistant("ok", Collections.<ToolCall>emptyList()));

        List<AgentMessage> result = compressor.compress(conv, budget, newRequest());

        // result 必须:含 assistant.tool_calls(cap.x) 时同时含 tool.observation
        boolean hasToolCall = false;
        boolean hasToolObs = false;
        for (AgentMessage m : result) {
            if (m.getRole() == AgentMessage.Role.ASSISTANT && !m.getToolCalls().isEmpty()) {
                hasToolCall = true;
            }
            if (m.getRole() == AgentMessage.Role.TOOL) {
                hasToolObs = true;
            }
        }
        assertTrue("tool_call 与 observation 必须要么都在 recent(都 true),要么都不在(都 false)。"
                + "实际:toolCall=" + hasToolCall + " obs=" + hasToolObs,
                hasToolCall == hasToolObs);
    }

    @Test
    public void fewerThanMaxTransactionsKeepsHeuristicMin() {
        // 仅 2 个 transaction(2 个 user+assistant 轮次)——HEURISTIC_KEEP_RECENT=4 兜底
        AgentBudget budget = new AgentBudget(8, 8, 60_000L, 50, 100, 64);
        ConversationCompressor compressor = new ConversationCompressor(null);
        List<AgentMessage> conv = new ArrayList<>();
        // 2 个 transaction = 4 条消息,触发压缩后 recent 至少 4 条
        conv.add(AgentMessage.user("u1 " + repeat("X", 60)));
        conv.add(AgentMessage.assistant("a1 " + repeat("X", 60), Collections.<ToolCall>emptyList()));
        conv.add(AgentMessage.user("u2 " + repeat("X", 60)));
        conv.add(AgentMessage.assistant("a2 " + repeat("X", 60), Collections.<ToolCall>emptyList()));

        // 加 2 个老 transaction 凑够 total 触发压缩
        conv.add(0, AgentMessage.user("u0a " + repeat("X", 60)));
        conv.add(1, AgentMessage.assistant("a0a " + repeat("X", 60), Collections.<ToolCall>emptyList()));
        // 现在 6 条消息,3 个 transaction

        List<AgentMessage> result = compressor.compress(conv, budget, newRequest());

        // recent 至少 4 条(HEURISTIC_KEEP_RECENT 兜底)
        assertTrue("recent 至少 " + ConversationCompressor.HEURISTIC_KEEP_RECENT + " 条,实际:"
                + (result.size() - 1), result.size() - 1 >= ConversationCompressor.HEURISTIC_KEEP_RECENT);
    }

    @Test
    public void transactionBoundaryDetection() {
        // 验证 isTransactionBoundary 的判定
        assertTrue("user 消息是 boundary",
                ConversationCompressor.isTransactionBoundary(AgentMessage.user("x")));
        assertTrue("assistant.tool_calls 非空是 boundary",
                ConversationCompressor.isTransactionBoundary(AgentMessage.assistant(null,
                        Arrays.asList(new ToolCall("cap", Collections.<String, Object>emptyMap())))));
        // assistant 纯文本不是 boundary
        assertTrue(!ConversationCompressor.isTransactionBoundary(
                AgentMessage.assistant("text", Collections.<ToolCall>emptyList())));
        // tool observation 不是 boundary(跟随父 assistant.tool_calls)
        assertTrue(!ConversationCompressor.isTransactionBoundary(
                AgentMessage.tool("step-1", "cap", "result")));
    }

    @Test
    public void threeTransactionsKeptAsRecentByDefault() {
        // 6 个 transaction,user-only:每个 user 是 1 个 transaction,6 个 user = 6 transactions
        List<AgentMessage> conv = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            conv.add(AgentMessage.user("user-msg-" + i));
        }
        List<AgentMessage> toSummarize = new ArrayList<>();
        List<AgentMessage> recent = new ArrayList<>();
        ConversationCompressor.splitByTransactions(conv, toSummarize, recent);

        // MAX_RECENT_TRANSACTIONS=3,但每个 transaction 只 1 条消息,recent 3 条 < HEURISTIC_KEEP_RECENT=4
        // → 继续向前取 1 个 transaction → recent 4 条
        assertEquals("HEURISTIC_KEEP_RECENT 兜底,recent 应 4 条", 4, recent.size());
        assertEquals("剩下 2 条 toSummarize", 2, toSummarize.size());
    }

    private static boolean sameGroup(List<AgentMessage> conv, List<AgentMessage> recent,
            List<AgentMessage> toSummarize, int... indices) {
        boolean inRecent = true;
        boolean inSummarize = true;
        for (int idx : indices) {
            AgentMessage m = conv.get(idx);
            if (!recent.contains(m)) inRecent = false;
            if (!toSummarize.contains(m)) inSummarize = false;
        }
        return inRecent || inSummarize;  // 全在 recent 或 全在 summarize
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
}
