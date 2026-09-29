package com.matrix.agent.launcher.overlay;

import com.matrix.agent.api.handoff.ExternalAppHandoffRequest;
import java.util.Objects;

/** Runtime ID may be absent only for a PRIMARY submission accepted inside the panel. */
public record OverlayBinding(String runtimeId, String conversationId, String taskId,
        String userMessageId, long userSequence) {
    public static OverlayBinding from(ExternalAppHandoffRequest request) {
        return new OverlayBinding(request.runtimeRequestId(), request.conversationId(),
                request.conversationTaskId(), request.hostUserMessageId(), request.hostUserSequence());
    }
    public boolean sameRound(OverlayBinding other) {
        return other != null && Objects.equals(conversationId, other.conversationId)
                && Objects.equals(taskId, other.taskId) && Objects.equals(userMessageId, other.userMessageId);
    }
}
