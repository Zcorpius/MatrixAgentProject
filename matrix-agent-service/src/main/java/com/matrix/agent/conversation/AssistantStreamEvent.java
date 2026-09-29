package com.matrix.agent.conversation;

/** Replaceable ephemeral snapshot; cleared events are tombstones, never persisted answers. */
public record AssistantStreamEvent(String conversationId, String taskId, String requestId,
        int turn, long sequence, String text, boolean cleared) { }
