package com.matrix.agent.conversation;

import java.util.UUID;

/**
 * 对话域标识派生与校验（Host 内部；所有 id 均 server-owned）。
 *
 * <p>agentSessionId 格式 {@code conv:<conversationId>:<actor>:<zone>}——
 * 与 durable task 的 taskId-as-session 契约刻意区分（设计文档 §5.3），
 * 连续聊天共享同一 working memory / steer mailbox 隔离键。</p>
 */
public final class ConversationIds {
    private static final String SESSION_PREFIX = "conv:";

    private ConversationIds() { }

    public static String newConversationId() {
        return UUID.randomUUID().toString();
    }

    public static String newMessageId() {
        return UUID.randomUUID().toString();
    }

    public static String newConversationTaskId() {
        return UUID.randomUUID().toString();
    }

    public static String newRuntimeRequestId() {
        return UUID.randomUUID().toString();
    }

    /** conv:<conversationId>:<actor>:<zone>；actor/zone 用大写名保持稳定。 */
    public static String agentSessionId(String conversationId, String actor, String zone) {
        requireLowerUuid(conversationId, "conversationId");
        if (actor == null || actor.isBlank()) throw new IllegalArgumentException("actor 不能为空");
        if (zone == null || zone.isBlank()) throw new IllegalArgumentException("zone 不能为空");
        return SESSION_PREFIX + conversationId + ":" + actor + ":" + zone;
    }

    public static boolean isLowerUuid(String value) {
        if (value == null) return false;
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    public static String requireLowerUuid(String value, String name) {
        if (!isLowerUuid(value)) {
            throw new IllegalArgumentException(name + " 必须是小写 UUID");
        }
        return value;
    }

    /**
     * TEXT 幂等键派生：必须包含 conversationId——clientOperationId 全局唯一索引下，
     * 防止不同会话的同名操作号碰撞（设计文档 §5.2）。
     */
    public static String textIdempotencyKey(String conversationId, String clientOperationId) {
        requireLowerUuid(conversationId, "conversationId");
        requireLowerUuid(clientOperationId, "clientOperationId");
        return "text:" + conversationId + ":" + clientOperationId;
    }

    /** PTT = voiceSessionId + finalOrdinal（首条 final 为 1）。 */
    public static String pttIdempotencyKey(String voiceSessionId, int finalOrdinal) {
        return "ptt:" + requireLowerUuid(voiceSessionId, "voiceSessionId") + ":" + finalOrdinal;
    }

    /** WAKE = wakeEventId + finalOrdinal。 */
    public static String wakeIdempotencyKey(String wakeEventId, int finalOrdinal) {
        return "wake:" + requireLowerUuid(wakeEventId, "wakeEventId") + ":" + finalOrdinal;
    }

    /**
     * STEER 附属输入幂等键（评估 v1.0 §4.3）。独立前缀让全局唯一的 idempotency_key
     * 隐含 (conversationId, clientOperationId, inputKind=STEER) 唯一性——与主提交
     * (text:) 命名空间互斥，Binder 重试命中同一行而不会误伤主提交。
     */
    public static String steerIdempotencyKey(String conversationId, String clientOperationId) {
        requireLowerUuid(conversationId, "conversationId");
        requireLowerUuid(clientOperationId, "clientOperationId");
        return "steer:" + conversationId + ":" + clientOperationId;
    }
}
