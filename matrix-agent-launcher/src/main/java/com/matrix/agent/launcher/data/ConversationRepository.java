package com.matrix.agent.launcher.data;

import androidx.annotation.NonNull;

import com.matrix.agent.api.conversation.ConversationInfo;
import com.matrix.agent.api.conversation.ConversationListQuery;
import com.matrix.agent.client.ConversationManager;
import com.matrix.agent.api.conversation.ConversationMessage;
import com.matrix.agent.api.conversation.ConversationPage;
import com.matrix.agent.api.conversation.ConversationSubmission;
import com.matrix.agent.api.conversation.CreateConversationRequest;
import com.matrix.agent.api.conversation.SendTextRequest;

import com.matrix.agent.launcher.data.LauncherHostGateway.Result;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** SDK-backed conversation data source. UI never sees a Manager instance or Binder type. */
public final class ConversationRepository {

    /** 订阅快照之后一次性翻页取的每页条数。 */
    public static final int PAGE_SIZE = 30;

    /** Launcher 自有事件端口；presentation 不接触 SDK listener 类型（边界规则）。 */
    public interface ConversationListener {
        default void onMessageUpsert(ConversationMessage message) { }
        default void onMessageStatusChanged(String conversationId, String messageId,
                int status, int errorCode) { }
        default void onTransientTranscript(String conversationId, String voiceSessionId,
                String text, boolean isFinal) { }
        default void onConversationError(String conversationId, int errorCode) { }
    }

    private final LauncherHostGateway gateway;

    public ConversationRepository(LauncherHostGateway gateway) {
        this.gateway = gateway;
    }

    public boolean isHostConnected() {
        return gateway.isConnected();
    }

    public void createConversation(String title,
            @NonNull Consumer<Result<ConversationInfo>> receiver) {
        gateway.execute(agent -> {
            ConversationManager manager = agent.getConversationManager();
            return manager == null ? null : manager.createConversation(
                    new CreateConversationRequest(title, null), java.util.UUID.randomUUID()
                            .toString());
        }, receiver);
    }

    public void listConversations(@NonNull Consumer<Result<List<ConversationInfo>>> receiver) {
        gateway.execute(agent -> {
            ConversationManager manager = agent.getConversationManager();
            return manager == null ? null : manager.listConversations(
                    new ConversationListQuery(false, ConversationListQuery.DEFAULT_LIMIT));
        }, receiver);
    }

    public void pageMessages(@NonNull String conversationId, long beforeSequenceExclusive,
            @NonNull Consumer<Result<ConversationPage>> receiver) {
        gateway.execute(agent -> {
            ConversationManager manager = agent.getConversationManager();
            return manager == null ? null : manager.getMessages(conversationId,
                    beforeSequenceExclusive, PAGE_SIZE);
        }, receiver);
    }

    public void sendText(@NonNull String conversationId, @NonNull String text,
            @NonNull String clientOperationId,
            @NonNull Consumer<Result<ConversationSubmission>> receiver) {
        gateway.execute(agent -> {
            ConversationManager manager = agent.getConversationManager();
            return manager == null ? null : manager.sendText(
                    new SendTextRequest(conversationId, text, null), clientOperationId);
        }, receiver);
    }

    public void appendMessage(@NonNull String conversationId, @NonNull String text,
            @NonNull String clientOperationId,
            @NonNull Consumer<Result<com.matrix.agent.api.conversation.ConversationOperationResult>> receiver) {
        gateway.execute(agent -> {
            ConversationManager manager = agent.getConversationManager();
            return manager == null ? null : manager.appendMessage(conversationId, text,
                    clientOperationId);
        }, receiver);
    }

    public void cancelMessage(@NonNull String conversationId, @NonNull String messageId,
            @NonNull String clientOperationId,
            @NonNull Consumer<Result<com.matrix.agent.api.conversation.ConversationOperationResult>> receiver) {
        gateway.execute(agent -> {
            ConversationManager manager = agent.getConversationManager();
            return manager == null ? null : manager.cancelMessage(conversationId, messageId,
                    clientOperationId);
        }, receiver);
    }

    public AutoCloseable subscribe(@NonNull String conversationId,
            @NonNull ConversationListener listener,
            @NonNull Consumer<Result<AutoCloseable>> receiver) {
        final AutoCloseable[] handle = new AutoCloseable[1];
        gateway.execute(agent -> {
            ConversationManager manager = agent.getConversationManager();
            if (manager == null) return null;
            handle[0] = manager.subscribeConversation(conversationId,
                    new ConversationManager.ConversationListener() {
                        @Override public void onMessageUpsert(ConversationMessage message) {
                            listener.onMessageUpsert(message);
                        }
                        @Override public void onMessageStatusChanged(String conversationId,
                                String messageId, int status, int errorCode) {
                            listener.onMessageStatusChanged(conversationId, messageId, status,
                                    errorCode);
                        }
                        @Override public void onTransientTranscript(String conversationId,
                                String voiceSessionId, String text, boolean isFinal) {
                            listener.onTransientTranscript(conversationId, voiceSessionId, text,
                                    isFinal);
                        }
                        @Override public void onConversationError(String conversationId,
                                int errorCode) {
                            listener.onConversationError(conversationId, errorCode);
                        }
                    });
            return handle[0];
        }, receiver);
        return () -> {
            try {
                if (handle[0] != null) handle[0].close();
            } catch (Exception ignored) {
                // 进程销毁路径；Binder 已死时 SDK 侧为 no-op。
            }
        };
    }

    // ---------------------------------------------------------------- PTT 语音

    /** 创建 PTT 绑定：返回 bindingOperationId（作为 PTT clientOperationId）。 */
    public void createVoiceBinding(@NonNull String conversationId,
            @NonNull String clientOperationId,
            @NonNull Consumer<Result<String>> receiver) {
        gateway.execute(agent -> {
            var manager = agent.getConversationManager();
            if (manager == null) return null;
            return manager.createVoiceBinding(conversationId, clientOperationId);
        }, receiver);
    }

    /** 启动 PTT 语音会话（bindingOperationId 作为 clientOperationId）。 */
    public void startVoiceSession(@NonNull String bindingOperationId,
            @NonNull VoiceInputListener listener,
            @NonNull Consumer<Result<com.matrix.agent.api.voice.VoiceSessionHandle>> receiver) {
        gateway.execute(agent -> {
            var manager = agent.getVoiceManager();
            if (manager == null) return null;
            var request = new com.matrix.agent.api.voice.VoiceSessionRequest(
                    com.matrix.agent.api.voice.VoiceSessionRequest.TRIGGER_PTT,
                    Locale.getDefault().toLanguageTag());
            return manager.startUserInitiatedSession(request, bindingOperationId,
                    new com.matrix.agent.client.VoiceSessionListener() {
                        @Override public void onSessionStateChanged(String s, int st) {
                            listener.onStateChanged(s, st);
                        }
                        @Override public void onPartialText(String s, String t) {
                            listener.onPartialText(s, t);
                        }
                        @Override public void onFinalText(String s, String t) {
                            listener.onFinalText(s, t);
                        }
                        @Override public void onSessionError(String s, int e) {
                            listener.onError(s, e);
                        }
                    });
        }, receiver);
    }

    public interface VoiceInputListener {
        default void onStateChanged(String sessionId, int state) { }
        default void onPartialText(String sessionId, String text) { }
        default void onFinalText(String sessionId, String text) { }
        default void onError(String sessionId, int errorCode) { }
    }

    /** PTT 松开发送：冲刷识别结果 → final 自动进入对话。 */
    public void finishRecording(@NonNull String sessionId, @NonNull String clientOperationId,
            @NonNull Consumer<Result<com.matrix.agent.api.voice.VoiceOperationResult>> receiver) {
        gateway.execute(agent -> {
            var manager = agent.getVoiceManager();
            if (manager == null) return null;
            return manager.finishSession(sessionId, clientOperationId);
        }, receiver);
    }
}
