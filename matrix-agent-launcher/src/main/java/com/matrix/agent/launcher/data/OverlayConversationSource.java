package com.matrix.agent.launcher.data;

import com.matrix.agent.api.conversation.*;
import java.util.List;
import java.util.function.Consumer;

/** Narrow conversation port for the application-scoped mini panel; callbacks are main-thread delivered. */
public interface OverlayConversationSource {
    boolean isHostConnected();
    default AutoCloseable subscribeDebug(ConversationRepository.DebugTraceListener listener,
            Consumer<LauncherHostGateway.Result<AutoCloseable>> receiver) {
        receiver.accept(LauncherHostGateway.Result.success(() -> {}));
        return () -> {};
    }
    default void loadDebugHistory(String userMessageId,
            Consumer<LauncherHostGateway.Result<List<com.matrix.agent.api.debug.DebugTraceWireEvent>>> receiver) {
        receiver.accept(LauncherHostGateway.Result.success(List.of()));
    }
    AutoCloseable subscribe(String conversationId, ConversationRepository.ConversationListener listener,
            Consumer<LauncherHostGateway.Result<AutoCloseable>> receiver);
    void pageMessages(String conversationId, long beforeSequenceExclusive,
            Consumer<LauncherHostGateway.Result<ConversationPage>> receiver);
    void messagesAround(String conversationId, long sequence,
            Consumer<LauncherHostGateway.Result<ConversationPage>> receiver);
    void messagesAfter(String conversationId, long sequence,
            Consumer<LauncherHostGateway.Result<ConversationPage>> receiver);
    void cancelMessage(String conversationId, String messageId, String operationId,
            Consumer<LauncherHostGateway.Result<ConversationOperationResult>> receiver);
    void submitTextOrAppend(String conversationId, String text, List<String> attachments,
            ConversationDraft draft, String operationId,
            Consumer<LauncherHostGateway.Result<ConversationSubmission>> receiver);
}
