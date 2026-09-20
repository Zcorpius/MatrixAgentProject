package com.matrix.agent.task.conversation;

import android.util.Log;

import com.matrix.agent.contract.AgentMessage;
import com.matrix.agent.contract.ConversationSeedContext;
import com.matrix.agent.task.AgentBudget;
import com.matrix.agent.task.redact.ModelSanitizer;

import java.util.ArrayList;
import java.util.List;

/**
 * 跨任务对话历史 → 模型种子的唯一装配入口（设计文档 §5.4、§12“双重历史”风险行）。
 *
 * <p>在 keyed lane 出队时调用（同 conversation 前序任务已终态，历史不遗漏上一轮结果）。
 * 策略：从最新回合向前收纳，直到触及种子字符预算（总预算的固定比例）；
 * 每条文本过 ModelSanitizer（凭据清洗 + 单条上限）。当前 user 消息不参与——
 * 它由 Engine 在种子之后追加，天然保序与优先。</p>
 */
public final class ConversationContextAssembler {

    private static final String TAG = "MatrixAgent";

    /** 单次种子最多收纳的历史回合数上限（防御性，正常远达不到）。 */
    static final int MAX_SEED_ENTRIES = 16;

    /** 种子可占用的总输入字符预算比例；剩余留给系统提示、工具与当轮指令。 */
    static final double SEED_BUDGET_RATIO = 0.4;

    private final AgentBudget budget;
    private final ConversationHistorySource history;

    public ConversationContextAssembler(AgentBudget budget, ConversationHistorySource history) {
        if (budget == null) throw new IllegalArgumentException("budget 不能为空");
        if (history == null) throw new IllegalArgumentException("history 不能为空");
        this.budget = budget;
        this.history = history;
    }

    /** 组装种子；无可用历史或预算为零时返回空种子（Engine 跳过注入）。 */
    public ConversationSeedContext assemble(String conversationId, String currentUserText) {
        if (conversationId == null || conversationId.trim().isEmpty()) {
            return new ConversationSeedContext(List.of());
        }
        List<ConversationHistorySource.HistoryEntry> entries =
                history.latestCompleted(conversationId, MAX_SEED_ENTRIES);
        if (entries.isEmpty()) {
            return new ConversationSeedContext(List.of());
        }

        int seedBudget = (int) (budget.getTotalInputChars() * SEED_BUDGET_RATIO);
        ModelSanitizer sanitizer = new ModelSanitizer(budget.getMaxMessageChars());

        // 从最新向前收纳（保留最近的上下文），再反转为时间升序。
        List<ConversationHistorySource.HistoryEntry> kept = new ArrayList<>(entries.size());
        int used = 0;
        int userEstimate = currentUserText == null ? 0 : currentUserText.length();
        if (seedBudget <= userEstimate) {
            return new ConversationSeedContext(List.of());
        }
        for (int i = entries.size() - 1; i >= 0; i--) {
            ConversationHistorySource.HistoryEntry entry = entries.get(i);
            String sanitized = sanitizer.sanitize(entry.text());
            if (used + sanitized.length() > seedBudget - userEstimate) {
                Log.d(TAG, "[SeedAssembler] 预算触顶：收纳 " + kept.size() + "/"
                        + entries.size() + " 条历史");
                break;
            }
            kept.add(entry.withText(sanitized));
            used += sanitized.length();
        }

        List<AgentMessage> seedMessages = new ArrayList<>(kept.size());
        for (int i = kept.size() - 1; i >= 0; i--) { // 反转回时间升序
            ConversationHistorySource.HistoryEntry entry = kept.get(i);
            seedMessages.add(entry.fromUser()
                    ? AgentMessage.user(entry.text())
                    : AgentMessage.assistant(entry.text(), List.of()));
        }
        return new ConversationSeedContext(seedMessages);
    }
}
