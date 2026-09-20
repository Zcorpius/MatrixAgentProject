package com.matrix.agent.task.conversation;

import java.util.List;

/**
 * task 域读取“已完成对话回合”的唯一端口。conversation 域提供实现（经其持久层投影），
 * 依赖方向 conversation → task（§4.2），task 不感知任何存储细节。
 */
public interface ConversationHistorySource {

    /**
     * 返回该 conversation 最近 {@code maxEntries} 条可进入模型上下文的完成回合，
     * <b>按时间升序</b>（最旧在前，超出 maxEntries 时丢弃更旧条目）。
     * 只包含 COMPLETED 的 user/assistant 文本；
     * FAILED/REJECTED/SYSTEM 与一切技术细节由实现侧过滤。
     */
    List<HistoryEntry> latestCompleted(String conversationId, int maxEntries);

    /** 单条历史：fromUser 区分角色（false = assistant）。 */
    record HistoryEntry(boolean fromUser, String text) {
        public HistoryEntry {
            if (text == null) throw new IllegalArgumentException("text 不能为空");
        }

        /** 装配器清洗后的副本（角色不变）。 */
        public HistoryEntry withText(String sanitized) {
            return new HistoryEntry(fromUser, sanitized);
        }
    }
}
