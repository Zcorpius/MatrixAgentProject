package com.matrix.agent.data.memory;

/**
 * 四层 Memory 抽象——区分瞬时上下文、事件记忆、语义知识、用户偏好。
 *
 * <p>每层有独立的 source 接口与召回策略;MemoryRouter 按 layer 优先级合并。
 * 实现:Working(SessionContext recentTurns)+ Preference(MemoryStore.getAllPreferences);
 * Episodic / Semantic 占位返回空,接 SessionHistoryDao + embedding。
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
