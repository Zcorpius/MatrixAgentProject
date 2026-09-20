package com.matrix.agent.task.conversation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.matrix.agent.contract.AgentMessage;
import com.matrix.agent.contract.ConversationSeedContext;
import com.matrix.agent.task.AgentBudget;
import com.matrix.agent.task.conversation.ConversationHistorySource.HistoryEntry;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** 种子装配：预算、顺序、清洗与当前指令保留（设计文档 §5.4 / §11.1 assembler 行）。 */
public final class ConversationContextAssemblerTest {

    private static final class FakeHistory implements ConversationHistorySource {
        final List<HistoryEntry> entries = new ArrayList<>();

        @Override public List<HistoryEntry> latestCompleted(String conversationId, int maxEntries) {
            // 契约：最近 N 条（时间升序、超窗丢更旧）
            if (entries.size() <= maxEntries) {
                return new ArrayList<>(entries);
            }
            return new ArrayList<>(
                    entries.subList(entries.size() - maxEntries, entries.size()));
        }
    }

    @Test public void emptyHistoryProducesEmptySeed() {
        FakeHistory history = new FakeHistory();
        ConversationContextAssembler assembler =
                new ConversationContextAssembler(new AgentBudget(), history);
        ConversationSeedContext seed = assembler.assemble("conv", "当前指令");
        assertTrue(seed.isEmpty());
    }

    @Test public void entriesBecomeUserAssistantMessagesInChronologicalOrder() {
        FakeHistory history = new FakeHistory();
        history.entries.add(new HistoryEntry(true, "调高亮度"));
        history.entries.add(new HistoryEntry(false, "已将亮度调整为 80%。"));
        ConversationContextAssembler assembler =
                new ConversationContextAssembler(new AgentBudget(), history);

        ConversationSeedContext seed = assembler.assemble("conv", "再低一点");
        List<AgentMessage> messages = seed.messages();
        assertEquals(2, messages.size());
        assertEquals(AgentMessage.Role.USER, messages.get(0).getRole());
        assertEquals("调高亮度", messages.get(0).getContent());
        assertEquals(AgentMessage.Role.ASSISTANT, messages.get(1).getRole());
        assertEquals("已将亮度调整为 80%。", messages.get(1).getContent());
    }

    @Test public void budgetKeepsNewestTurnsAndDropsOldest() {
        FakeHistory history = new FakeHistory();
        for (int i = 0; i < 10; i++) {
            history.entries.add(new HistoryEntry(true, "第" + i + "轮指令"));
            history.entries.add(new HistoryEntry(false, "第" + i + "轮回复内容"));
        }
        // 小预算：种子预算 = 60 * 0.4 = 24 字符；指令 2 字符 → 余量 22；
        // 每轮约 12+14=26 字符 → 最多保留最新一轮，其余丢弃
        AgentBudget small = new AgentBudget(8, 8, 60_000L, 8_000, 60, 64);
        ConversationContextAssembler assembler = new ConversationContextAssembler(small, history);
        ConversationSeedContext seed = assembler.assemble("conv", "继续");

        // 预算触顶：只保留最新的若干轮；且保留的是“最新”而非“最旧”
        assertTrue("预算触顶应丢弃大部分历史", seed.messages().size() < 20);
        if (!seed.messages().isEmpty()) {
            AgentMessage last = seed.messages().get(seed.messages().size() - 1);
            assertEquals(AgentMessage.Role.ASSISTANT, last.getRole());
            assertTrue(last.getContent().contains("第9轮"));
        }
        // 最旧的一轮绝不在保留集内
        for (AgentMessage message : seed.messages()) {
            assertTrue(!message.getContent().contains("第0轮"));
        }
    }

    @Test public void currentInstructionBudgetRespected() {
        FakeHistory history = new FakeHistory();
        history.entries.add(new HistoryEntry(true, "历史"));
        // 预算刚够当前指令 + 一点种子 → 种子被迫放弃（当前指令优先）
        AgentBudget tight = new AgentBudget(8, 8, 60_000L, 8_000, 10, 64);
        ConversationContextAssembler assembler = new ConversationContextAssembler(tight, history);
        ConversationSeedContext seed = assembler.assemble("conv", "一二三四五");
        // 10 字符预算 - 5 字符指令 = 5 字符种子余量 < "历史" 的 2 字符？ 2<=5 可放
        // 用更紧：指令 8 字符 → 余量 2 仍可放 2 字符。改为指令几乎占满：
        ConversationSeedContext tightSeed =
                new ConversationContextAssembler(tight, history).assemble("conv", "一二三四五六七八");
        assertTrue(tightSeed.isEmpty());
    }

    @Test public void credentialsSanitizedInSeed() {
        FakeHistory history = new FakeHistory();
        history.entries.add(new HistoryEntry(false,
                "配置 sk-abcdef0123456789abcdef 已保存"));
        ConversationContextAssembler assembler =
                new ConversationContextAssembler(new AgentBudget(), history);
        ConversationSeedContext seed = assembler.assemble("conv", "继续");
        assertTrue(seed.messages().get(0).getContent().contains("***"));
    }
}
