package com.matrix.agent.conversation;

import android.util.Log;

import com.matrix.agent.api.conversation.ConversationMessage;
import com.matrix.agent.task.conversation.ConversationHistorySource;

import java.util.ArrayList;
import java.util.List;

/**
 * ConversationStore → task {@link ConversationHistorySource} 的适配器：
 * 只投影 COMPLETED 的 user/assistant 文本（§5.4-2），升序返回。
 * 依赖方向 conversation → task（§4.2），task 不感知存储。
 */
public final class ConversationHistoryAdapter implements ConversationHistorySource {

    private static final String TAG = "MatrixAgent";

    private final ConversationStore store;

    public ConversationHistoryAdapter(ConversationStore store) {
        this.store = store;
    }

    @Override
    public List<HistoryEntry> latestCompleted(String conversationId, int maxEntries) {
        // Store 侧已过滤 COMPLETED + USER/ASSISTANT 并按时间升序返回；
        // 端口语义要求“最近 N 条”——超窗时丢弃更旧条目，保留最新窗口。
        List<ConversationStore.MessageRow> all =
                store.latestCompletedForSeed(conversationId, maxEntries);
        List<ConversationStore.MessageRow> rows =
                all.size() > maxEntries
                        ? new ArrayList<>(all.subList(all.size() - maxEntries, all.size()))
                        : all;
        List<HistoryEntry> entries = new ArrayList<>(rows.size());
        for (ConversationStore.MessageRow row : rows) {
            if (row.text() == null || row.text().isBlank()) {
                continue;
            }
            entries.add(new HistoryEntry(
                    row.roleWire() == ConversationMessage.ROLE_USER, row.text()));
        }
        return entries;
    }

}
