package com.matrix.agent.conversation;

import com.matrix.agent.task.AgentBudget;
import com.matrix.agent.task.conversation.ConversationHistorySource;
import com.matrix.agent.task.conversation.ConversationHistorySource.HistoryEntry;

import java.util.List;

/**
 * 摘要续聊标记的读时重算判定（评估 v1.0 §4.6 / 阶段 2）。
 *
 * <p>复用 {@code ConversationCompressor} 的纯预算触发口径（80% 阈值，
 * {@code ConversationCompressorEightyPercentTriggerTest} 覆盖的同一常量语义），
 * 对“下一次续聊是否会使用摘要”做确定性重算：<b>不调用 LLM、不生成摘要、不写库、
 * 不发起任务</b>。命中即展示标记——它表达的是当前续聊的真实装配策略，
 * 不声称不可验证的历史事实。</p>
 */
public final class ConversationSummaryMarker {

    /** 与 ConversationCompressor.COMPRESSION_TRIGGER_RATIO 同源（0.8）。 */
    static final double TRIGGER_RATIO = 0.8;

    private final ConversationHistorySource historySource;
    private final AgentBudget budget;

    public ConversationSummaryMarker(ConversationHistorySource historySource,
            AgentBudget budget) {
        this.historySource = historySource;
        this.budget = budget;
    }

    /**
     * 下一次在该会话续聊是否会触发压缩（摘要装配）。
     *
     * <p>口径对齐压缩器入口：历史字符总量 &gt; 预算 × 0.8 即命中——预留的 20% 余量
     * 语义与真实装配完全同源，不另立第二套判定。</p>
     */
    public boolean wouldSummarizeOnNextRound(String conversationId) {
        int threshold = (int) (budget.getTotalInputChars() * TRIGGER_RATIO);
        if (threshold <= 0) {
            return false;
        }
        List<HistoryEntry> entries = historySource.latestCompleted(conversationId,
                Integer.MAX_VALUE);
        int totalChars = 0;
        for (HistoryEntry entry : entries) {
            totalChars += entry.text() == null ? 0 : entry.text().length();
            if (totalChars > threshold) {
                return true;
            }
        }
        return false;
    }
}
