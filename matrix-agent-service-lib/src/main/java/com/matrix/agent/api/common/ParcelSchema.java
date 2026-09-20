package com.matrix.agent.api.common;

/**
 * Parcelable 契约版本。所有 DTO 携带 schemaVersion；演进规则：只追加字段，
 * 反序列化按版本容错（旧客户端读到缺失字段取默认值），不允许改动既有字段语义。
 */
public final class ParcelSchema {

    /** 当前契约版本（contractMinor 每次 DTO 追加字段时递增）。 */
    /**
     * v2 appends the stable acceptance/rejection code to AgentTaskHandle.
     * v3 appends presentation fields to ConversationInfo (titleOrigin/pinned/
     * lastInputChannel) and window flags to ConversationPage (hasBefore/hasAfter/
     * anchorExists).
     * v4 appends quotedMessageId to SendTextRequest (quote-reply, evaluation §4.4).
     * v5 appends to ConversationMessage: inputKind / steerHostUserMessageId /
     * steerDeliveryState (steer auxiliary-input note) and executionTraces
     * (capability execution fact projection, user-message-carried).
     * Readers must branch on schemaVersion before consuming appended fields;
     * writers remain append-only.
     */
    public static final int CURRENT = 5;

    private ParcelSchema() {
    }
}
