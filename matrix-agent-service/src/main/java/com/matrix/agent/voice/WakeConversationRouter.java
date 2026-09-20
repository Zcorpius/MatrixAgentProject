package com.matrix.agent.voice;

import android.util.Log;

import com.matrix.agent.conversation.ConversationCoordinator;
import com.matrix.agent.conversation.ConversationStore.ConversationRow;

import java.util.List;

/**
 * 唤醒路由器（设计文档 §6.3）：wake final 提交前决定投递到哪个 conversation。
 *
 * <p>默认规则（§6.3-1/2）：该 Actor+zone 存在最近活动未超时（10 分钟）的未归档线程则续接，
 * 否则新建"语音对话"线程。被显式删除/归档的线程永不被复活（§6.3-4）。
 * 路由在 Host 进程内执行，不依赖 Launcher 前台（§6.3-5）。
 */
public final class WakeConversationRouter {

    private static final String TAG = "MatrixAgent";
    /** 续接窗口（毫秒）：最近活动距今超过此值则新建线程。 */
    private static final long RECENT_WINDOW_MS = 10 * 60 * 1000;

    private final ConversationCoordinator coordinator;
    private final String ownerUserId;
    private final String zone;

    public WakeConversationRouter(ConversationCoordinator coordinator,
            String ownerUserId, String zone) {
        this.coordinator = coordinator;
        this.ownerUserId = ownerUserId;
        this.zone = zone;
    }

    /**
     * 解析 wake final 应投递的 conversationId。
     * 续接最近未归档线程或新建；返回的 id 一定存在（新建保证）。
     */
    public String resolve() {
        List<ConversationRow> conversations =
                coordinator.listConversations(ownerUserId, false, 5);
        long now = System.currentTimeMillis();
        for (ConversationRow row : conversations) {
            if (row.archived()) continue; // 归档线程不复活（§6.3-4）
            if (now - row.updatedAtMs() > RECENT_WINDOW_MS) {
                Log.d(TAG, "[WakeRouter] 线程超时 conv=" + row.conversationId()
                        + " ageMs=" + (now - row.updatedAtMs()));
                continue;
            }
            Log.i(TAG, "[WakeRouter] 续接最近线程 conv=" + row.conversationId()
                    + " ageMs=" + (now - row.updatedAtMs()));
            // 续接即活动（评估 v1.0 §4.1）：touch updated_at_ms 让"最近使用"排序
            // 如实反映语音交互；lastInputChannel 在 final 提交落库时盖章。
            coordinator.touchConversationActivity(row.conversationId());
            return row.conversationId();
        }
        // 新建
        ConversationRow created = coordinator.createConversation(ownerUserId, zone,
                "语音对话");
        Log.i(TAG, "[WakeRouter] 新建语音线程 conv=" + created.conversationId());
        return created.conversationId();
    }
}
