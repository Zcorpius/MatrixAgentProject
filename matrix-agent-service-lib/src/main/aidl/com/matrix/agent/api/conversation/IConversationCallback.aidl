package com.matrix.agent.api.conversation;

import com.matrix.agent.api.conversation.ConversationMessage;

/**
 * 对话事件回调。注册后 Host 先回放一个有界快照（最近 N 条 onMessageUpsert），
 * 之后推送增量；同一事件允许重复送达，客户端按 messageId/sequence 合并。
 */
oneway interface IConversationCallback {
    /** 消息插入或内容更新（含快照回放与终态 assistant 消息）。 */
    void onMessageUpsert(in ConversationMessage message);

    /** 仅状态/错误码变化（ACCEPTED→RUNNING→终态）。 */
    void onMessageStatusChanged(String conversationId, String messageId, int status,
            int errorCode);

    /** 语音会话的临时转写（partial/flush）；只在语音绑定生效时出现，final 后清除。 */
    void onTransientTranscript(String conversationId, String voiceSessionId, String text,
            boolean isFinal);

    void onConversationError(String conversationId, int errorCode);
}
