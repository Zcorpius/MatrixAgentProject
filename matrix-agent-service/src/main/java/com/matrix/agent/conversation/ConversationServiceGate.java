package com.matrix.agent.conversation;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 对话域准入门（设计文档 §5.3）：恢复对账完成前，IConversationService 全部方法
 * fail-closed（对齐 PersistenceGate / TaskGraph.recoveryComplete 的既有模式）。
 * 一次性——open() 之后不再关闭（进程级生命周期）。
 */
public final class ConversationServiceGate {

    private final AtomicBoolean open = new AtomicBoolean(false);

    public void open() {
        open.set(true);
    }

    public boolean isAvailable() {
        return open.get();
    }
}
