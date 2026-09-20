package com.matrix.agent.client;

import android.os.IBinder;
import android.os.RemoteException;

import com.matrix.agent.api.common.MatrixErrorCode;
import com.matrix.agent.api.conversation.ConversationInfo;
import com.matrix.agent.api.conversation.ConversationListQuery;
import com.matrix.agent.api.conversation.ConversationMessage;
import com.matrix.agent.api.conversation.ConversationOperationResult;
import com.matrix.agent.api.conversation.ConversationPage;
import com.matrix.agent.api.conversation.ConversationSubmission;
import com.matrix.agent.api.conversation.CreateConversationRequest;
import com.matrix.agent.api.conversation.IConversationCallback;
import com.matrix.agent.api.conversation.IConversationService;
import com.matrix.agent.api.conversation.SendTextRequest;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 对话域 Manager：线程列表、历史分页、文字提交与事件订阅。
 *
 * <p>订阅句柄为 {@link AutoCloseable}：close() 显式退订；Binder 死亡后 proxy 失效，
 * 方法返回与 {@code getState()} 一致的稳定不可用结果（空列表 / FAILED 提交 / 空页），
 * 重连由门面自动换绑。回调一律桥接为纯 Java listener 并投递到门面事件 Handler。</p>
 */
public final class ConversationManager extends MatrixManagerBase {

    private volatile IConversationService service;

    ConversationManager(MatrixAgent matrixAgent, IBinder serviceBinder) {
        super(matrixAgent, serviceBinder);
        service = IConversationService.Stub.asInterface(serviceBinder);
    }

    public ConversationInfo createConversation(CreateConversationRequest request,
            String clientOperationId) {
        IConversationService s = service;
        if (s == null) return null;
        try {
            return s.createConversation(request, clientOperationId);
        } catch (RemoteException e) {
            return handleRemoteException(e, null);
        }
    }

    public List<ConversationInfo> listConversations(ConversationListQuery query) {
        IConversationService s = service;
        if (s == null) return Collections.emptyList();
        try {
            List<ConversationInfo> result = s.listConversations(query);
            return result == null ? Collections.emptyList() : result;
        } catch (RemoteException e) {
            return handleRemoteException(e, Collections.emptyList());
        }
    }

    public ConversationPage getMessages(String conversationId, long beforeSequenceExclusive,
            int limit) {
        IConversationService s = service;
        if (s == null) return unavailablePage();
        try {
            ConversationPage page = s.getMessages(conversationId, beforeSequenceExclusive, limit);
            return page == null ? unavailablePage() : page;
        } catch (RemoteException e) {
            return handleRemoteException(e, unavailablePage());
        }
    }

    public ConversationSubmission sendText(SendTextRequest request, String clientOperationId) {
        IConversationService s = service;
        if (s == null) return unavailableSubmission(request == null ? null
                : request.conversationId);
        try {
            ConversationSubmission result = s.sendText(request, clientOperationId);
            return result == null ? unavailableSubmission(request.conversationId) : result;
        } catch (RemoteException e) {
            return handleRemoteException(e, unavailableSubmission(request.conversationId));
        }
    }

    public ConversationOperationResult appendMessage(String conversationId, String text,
            String clientOperationId) {
        IConversationService s = service;
        if (s == null) return unavailableOperation(clientOperationId, conversationId);
        try {
            return s.appendMessage(conversationId, text, clientOperationId);
        } catch (RemoteException e) {
            return handleRemoteException(e, unavailableOperation(clientOperationId, conversationId));
        }
    }

    /** 创建一次性 PTT 绑定（§8.1）。返回 bindingOperationId（小写 UUID）。 */
    public String createVoiceBinding(String conversationId, String clientOperationId) {
        IConversationService s = service;
        if (s == null) return null;
        try {
            return s.createVoiceBinding(conversationId, clientOperationId);
        } catch (RemoteException e) {
            return handleRemoteException(e, null);
        }
    }

    public ConversationOperationResult cancelMessage(String conversationId, String messageId,
            String clientOperationId) {
        IConversationService s = service;
        if (s == null) return unavailableOperation(clientOperationId, conversationId);
        try {
            return s.cancelMessage(conversationId, messageId, clientOperationId);
        } catch (RemoteException e) {
            return handleRemoteException(e, unavailableOperation(clientOperationId, conversationId));
        }
    }

    /** listener 重载（常规入口）：close() 显式退订。 */
    public AutoCloseable subscribeConversation(String conversationId,
            ConversationListener listener) {
        Objects.requireNonNull(listener, "listener");
        return subscribeConversation(conversationId, new IConversationCallback.Stub() {
            @Override public void onMessageUpsert(ConversationMessage message) {
                eventHandler().post(() -> listener.onMessageUpsert(message));
            }

            @Override public void onMessageStatusChanged(String convId, String messageId,
                    int status, int errorCode) {
                eventHandler().post(() ->
                        listener.onMessageStatusChanged(convId, messageId, status, errorCode));
            }

            @Override public void onTransientTranscript(String convId, String voiceSessionId,
                    String text, boolean isFinal) {
                eventHandler().post(() ->
                        listener.onTransientTranscript(convId, voiceSessionId, text, isFinal));
            }

            @Override public void onConversationError(String convId, int errorCode) {
                eventHandler().post(() -> listener.onConversationError(convId, errorCode));
            }
        });
    }

    /** 低层扩展接口：直接暴露 AIDL callback；常规调用方用 listener 重载。 */
    public AutoCloseable subscribeConversation(String conversationId,
            IConversationCallback callback) {
        Objects.requireNonNull(callback, "callback");
        IConversationService s = service;
        if (s == null) return () -> { };
        try {
            s.subscribeConversation(conversationId, callback);
        } catch (RemoteException e) {
            handleRemoteException(e);
        }
        return () -> {
            IConversationService current = service;
            if (current == null) return;
            try {
                current.unsubscribeConversation(callback);
            } catch (RemoteException e) {
                handleRemoteException(e);
            }
        };
    }

    /** 纯 Java 对话事件 listener；SDK 桥接 Binder 回调后派发。 */
    public interface ConversationListener {
        default void onMessageUpsert(ConversationMessage message) { }
        default void onMessageStatusChanged(String conversationId, String messageId,
                int status, int errorCode) { }
        default void onTransientTranscript(String conversationId, String voiceSessionId,
                String text, boolean isFinal) { }
        default void onConversationError(String conversationId, int errorCode) { }
    }

    private static ConversationPage unavailablePage() {
        return new ConversationPage(Collections.emptyList(), false);
    }

    private static ConversationSubmission unavailableSubmission(String conversationId) {
        return new ConversationSubmission(MatrixErrorCode.SERVICE_NOT_READY, conversationId,
                null, null, 0L, false);
    }

    private static ConversationOperationResult unavailableOperation(String clientOperationId,
            String conversationId) {
        return new ConversationOperationResult(MatrixErrorCode.SERVICE_NOT_READY,
                clientOperationId, conversationId, null);
    }

    @Override protected void onMatrixServiceDisconnected() {
        service = null;
    }

    @Override protected void onMatrixServiceConnected(IBinder serviceBinder) {
        service = IConversationService.Stub.asInterface(serviceBinder);
    }
}
