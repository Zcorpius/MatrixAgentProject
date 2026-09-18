package com.matrix.agent.data.memory;

import com.matrix.agent.session.SessionContext;

/**
 * 四层 Memory 抽象——区分瞬时上下文、事件记忆、语义知识、用户偏好。
 *
 * <p>每层有独立的 source 接口与召回策略;MemoryRouter 按 layer 优先级合并。
 * 实现:Working(SessionContext recentTurns)、Episodic(SessionHistoryDao)、
 * Semantic(MemoryRecordDao 的关键词 + score 召回)、Preference(MemoryStore.getAllPreferences)。
 * 只有 SQLCipher 不可用的显式降级模式会让 Episodic / Semantic 返回空。
 */
public enum MemoryLayer {
    WORKING("working"),
    EPISODIC("episodic"),
    SEMANTIC("semantic"),
    PREFERENCE("preference");

    private final String wireValue;

    MemoryLayer(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
