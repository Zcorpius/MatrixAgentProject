package com.matrix.agent.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import com.matrix.agent.task.tool.ToolCall;

/**
 * LLM 摘要不压缩结构化事实——
 *
 * <p>{@code splitStructuredFromNatural} 把 toSummarize 切成 structuredKeep + toSummarizeNatural。
 * 含 tool_call / tool observation / POLICY / EXECUTION_UNKNOWN 的 transaction 整组进 structuredKeep,
 * 纯 user/assistant 文本进 toSummarizeNatural 让 LLM 摘要。
 */
public final class ConversationCompressorStructuredFactRetentionTest {

    /**
     * 纯自然对话 → 全部进 toSummarizeNatural,structuredKeep 空。
     */
    @Test
    public void pureNaturalConversationAllToNatural() {
        List<AgentMessage> toSummarize = new ArrayList<>();
        toSummarize.add(AgentMessage.user("打开空调"));
        toSummarize.add(AgentMessage.assistant("好的", Collections.emptyList()));

        List<AgentMessage> structuredKeep = new ArrayList<>();
        List<AgentMessage> natural = new ArrayList<>();
        ConversationCompressor.splitStructuredFromNatural(toSummarize, structuredKeep, natural);

        assertTrue("structuredKeep 应为空", structuredKeep.isEmpty());
        assertEquals("natural 全部 2 条", 2, natural.size());
    }

    /**
     * 含 ToolCall 的 transaction → 整组(assistant + 后续 tool)进 structuredKeep。
     */
    @Test
    public void toolCallTransactionAllToStructuredKeep() {
        ToolCall call = ToolCall.withId("step-1", "climate.set_temperature",
                Collections.<String, Object>singletonMap("temp", 24));
        List<AgentMessage> toSummarize = new ArrayList<>();
        toSummarize.add(AgentMessage.user("把温度设到 24 度"));
        toSummarize.add(AgentMessage.assistant("调用 climate.set_temperature",
                Collections.singletonList(call)));
        toSummarize.add(AgentMessage.tool("step-1", "climate.set_temperature",
                "EXECUTION_OK result={\"temp\":24}"));

        List<AgentMessage> structuredKeep = new ArrayList<>();
        List<AgentMessage> natural = new ArrayList<>();
        ConversationCompressor.splitStructuredFromNatural(toSummarize, structuredKeep, natural);

        // user 单独成 1 个 transaction → natural
        // assistant.tool_calls + tool → 1 个 transaction → structuredKeep(2 条)
        assertEquals("structuredKeep 含 assistant+tool", 2, structuredKeep.size());
        assertEquals("natural 含 user", 1, natural.size());
        assertEquals("natural 第一条是 user",
                AgentMessage.Role.USER, natural.get(0).getRole());
        assertEquals("structuredKeep 第一条是 assistant with toolCalls",
                AgentMessage.Role.ASSISTANT, structuredKeep.get(0).getRole());
        assertFalse("structuredKeep assistant 有 toolCalls",
                structuredKeep.get(0).getToolCalls().isEmpty());
        assertEquals("structuredKeep 第二条是 tool",
                AgentMessage.Role.TOOL, structuredKeep.get(1).getRole());
    }

    /**
     * POLICY 拒绝(以 role==TOOL 文本承载)→ structuredKeep。
     */
    @Test
    public void policyRejectionTransactionKept() {
        ToolCall call = ToolCall.withId("step-1", "climate.set_temperature",
                Collections.<String, Object>singletonMap("temp", 24));
        List<AgentMessage> toSummarize = new ArrayList<>();
        toSummarize.add(AgentMessage.assistant("调用 climate.set_temperature",
                Collections.singletonList(call)));
        toSummarize.add(AgentMessage.tool("step-1", "climate.set_temperature",
                "EXECUTION_FAILED POLICY_REJECTED zone mismatch"));

        List<AgentMessage> structuredKeep = new ArrayList<>();
        List<AgentMessage> natural = new ArrayList<>();
        ConversationCompressor.splitStructuredFromNatural(toSummarize, structuredKeep, natural);

        assertEquals("POLICY 拒绝整组进 structuredKeep", 2, structuredKeep.size());
        assertTrue("natural 空", natural.isEmpty());
    }

    /**
     * EXECUTION_UNKNOWN(以 role==TOOL 文本承载)→ structuredKeep。
     */
    @Test
    public void executionUnknownTransactionKept() {
        ToolCall call = ToolCall.withId("step-1", "navi.set_destination",
                Collections.<String, Object>singletonMap("destination", "公司"));
        List<AgentMessage> toSummarize = new ArrayList<>();
        toSummarize.add(AgentMessage.assistant("调用 navi.set_destination",
                Collections.singletonList(call)));
        toSummarize.add(AgentMessage.tool("step-1", "navi.set_destination",
                "EXECUTION_UNKNOWN capability timeout"));

        List<AgentMessage> structuredKeep = new ArrayList<>();
        List<AgentMessage> natural = new ArrayList<>();
        ConversationCompressor.splitStructuredFromNatural(toSummarize, structuredKeep, natural);

        assertEquals("EXECUTION_UNKNOWN 整组进 structuredKeep", 2, structuredKeep.size());
    }

    /**
     * 混合 transaction:tool_call + tool + 后续 assistant 文本答疑。
     * 全部属于同一 transaction(因为后续 assistant 文本不启动新 transaction,边界只在 user/assistant.tool_calls)。
     * 整组都进 structuredKeep。
     */
    @Test
    public void mixedTransactionAllStructured() {
        ToolCall call = ToolCall.withId("step-1", "vehicle.get_battery",
                Collections.<String, Object>emptyMap());
        List<AgentMessage> toSummarize = new ArrayList<>();
        toSummarize.add(AgentMessage.assistant("查询电量",
                Collections.singletonList(call)));
        toSummarize.add(AgentMessage.tool("step-1", "vehicle.get_battery", "85%"));
        // assistant 纯文本答疑 — 不启动新 transaction(因为没 toolCalls)
        toSummarize.add(AgentMessage.assistant("当前电量 85%", Collections.emptyList()));

        List<AgentMessage> structuredKeep = new ArrayList<>();
        List<AgentMessage> natural = new ArrayList<>();
        ConversationCompressor.splitStructuredFromNatural(toSummarize, structuredKeep, natural);

        // 整组 3 条都进 structuredKeep(因为同 transaction 内有 tool_call 即视为 structured)
        assertEquals("整组 3 条都进 structuredKeep", 3, structuredKeep.size());
        assertTrue("natural 空", natural.isEmpty());
    }

    /**
     * isStructuredTransaction:纯 user / 纯 assistant 文本 → false。
     */
    @Test
    public void isStructuredTransactionForNaturalOnly() {
        List<AgentMessage> naturalTxn = new ArrayList<>();
        naturalTxn.add(AgentMessage.user("你好"));
        naturalTxn.add(AgentMessage.assistant("你好,有什么可以帮你", Collections.emptyList()));
        assertFalse("natural txn not structured",
                ConversationCompressor.isStructuredTransaction(naturalTxn));
    }

    /**
     * isStructuredTransaction:含 ToolCall → true。
     */
    @Test
    public void isStructuredTransactionForToolCall() {
        ToolCall call = ToolCall.withId("s", "cap", Collections.emptyMap());
        List<AgentMessage> txn = new ArrayList<>();
        txn.add(AgentMessage.assistant("调用", Collections.singletonList(call)));
        assertTrue("tool_call txn is structured",
                ConversationCompressor.isStructuredTransaction(txn));
    }

    /**
     * isStructuredTransaction:含 TOOL role → true(POLICY / EXECUTION_UNKNOWN / Observation)。
     */
    @Test
    public void isStructuredTransactionForToolRole() {
        List<AgentMessage> txn = new ArrayList<>();
        txn.add(AgentMessage.tool("step-1", "cap", "EXECUTION_OK"));
        assertTrue("tool role txn is structured",
                ConversationCompressor.isStructuredTransaction(txn));
    }
}
