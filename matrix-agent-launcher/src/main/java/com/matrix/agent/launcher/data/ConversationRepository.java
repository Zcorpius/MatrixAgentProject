package com.matrix.agent.launcher.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.matrix.agent.api.conversation.ConversationDraft;
import com.matrix.agent.api.conversation.ConversationInfo;
import com.matrix.agent.api.conversation.ConversationOperationResult;
import com.matrix.agent.api.conversation.ConversationListQuery;
import com.matrix.agent.api.conversation.ConversationRuntimeStage;
import com.matrix.agent.client.ConversationManager;
import com.matrix.agent.api.conversation.ConversationMessage;
import com.matrix.agent.api.conversation.ConversationPage;
import com.matrix.agent.api.conversation.ConversationSubmission;
import com.matrix.agent.api.conversation.CreateConversationRequest;
import com.matrix.agent.api.conversation.SendTextRequest;
import com.matrix.agent.api.debug.DebugTraceWireEvent;
import com.matrix.agent.client.DebugTraceManager;
import com.matrix.agent.launcher.BuildConfig;

import com.matrix.agent.launcher.data.LauncherHostGateway.Result;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;

/** SDK-backed conversation data source. UI never sees a Manager instance or Binder type. */
public final class ConversationRepository {

    /** 订阅快照之后一次性翻页取的每页条数。 */
    public static final int PAGE_SIZE = 30;

    /** Launcher 自有事件端口；presentation 不接触 SDK listener 类型（边界规则）。 */
    public interface ConversationListener {
        default void onMessageUpsert(ConversationMessage message) { }
        default void onMessageStatusChanged(String conversationId, String messageId,
                int status, int errorCode) { }
        default void onConversationInfoChanged(ConversationInfo info) { }
        default void onTransientTranscript(String conversationId, String voiceSessionId,
                String text, boolean isFinal) { }
        default void onRuntimeStageChanged(ConversationRuntimeStage stage) { }
        default void onConversationError(String conversationId, int errorCode) { }
    }

    /** matrix.debugTraceUi=true 时启用的旁路；正常会话订阅完全不依赖它。 */
    @FunctionalInterface
    public interface DebugTraceListener {
        void onDebugTrace(DebugTraceWireEvent event);
    }

    private final LauncherHostGateway gateway;
    private final DraftCommandLane draftLane;

    /**
     * 兼容构造（JVM 测试）：草稿命令同步执行——调用线程天然串行，可测但不可用于
     * 生产主线程。生产装配必须用
     * {@link #ConversationRepository(LauncherHostGateway, DraftCommandLane)}
     * 注入 registry 的单线程 draftCommands。
     */
    public ConversationRepository(LauncherHostGateway gateway) {
        this(gateway, new DraftCommandLane(gateway, directExecutor()));
    }

    public ConversationRepository(LauncherHostGateway gateway, DraftCommandLane draftLane) {
        this.gateway = gateway;
        this.draftLane = draftLane;
    }

    private static java.util.concurrent.ExecutorService directExecutor() {
        return new java.util.concurrent.AbstractExecutorService() {
            @Override public void execute(Runnable command) { command.run(); }
            @Override public void shutdown() { }
            @Override public java.util.List<Runnable> shutdownNow() { return List.of(); }
            @Override public boolean isShutdown() { return false; }
            @Override public boolean isTerminated() { return false; }
            @Override public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) {
                return true; }
        };
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

    /**
     * 统一提交（输入交互增强 §3.2）：Host 在会话门控内原子判定 steer 或新主轮次。
     * 走草稿 lane——与 saveDraft 串行，杜绝“提交后迟到保存复活草稿”的客户端窗口。
     */
    public void submitTextOrAppend(@NonNull String conversationId, @NonNull String text,
            @NonNull List<String> contextAttachmentIds,
            @Nullable ConversationDraft submittedDraft, @NonNull String clientOperationId,
            @NonNull Consumer<Result<ConversationSubmission>> receiver) {
        final List<String> attachments = contextAttachmentIds == null
                ? List.<String>of() : contextAttachmentIds;
        draftLane.execute(conversationId, () -> {
            Result<ConversationSubmission> result = gateway.callOnCurrentThread(agent -> {
                ConversationManager manager = agent.getConversationManager();
                return manager == null ? null : manager.submitTextOrAppend(conversationId,
                        text, attachments,
                        submittedDraft == null ? null : submittedDraft.draftInstanceId,
                        submittedDraft == null ? 0L : submittedDraft.revision,
                        clientOperationId);
            });
            draftLane.deliver(result, receiver);
        });
    }

    // ---------------------------------------------------------------- 草稿（I4）

    /** 会话草稿读取；无草稿返回 null。走草稿 lane 保持与保存的全序。 */
    public void getDraft(@NonNull String conversationId,
            @NonNull Consumer<Result<ConversationDraft>> receiver) {
        draftLane.execute(conversationId, () -> {
            Result<ConversationDraft> result = gateway.callOnCurrentThread(agent -> {
                ConversationManager manager = agent.getConversationManager();
                return manager == null ? null : manager.getDraft(conversationId);
            });
            draftLane.deliver(result, receiver);
        });
    }

    /**
     * 草稿保存。返回码经 Result.value 交付（null = 传输失败）：
     * SUCCESS / INVALID_STATE（instance 已消费，调用方换新 instance）/ INVALID_ARGUMENT。
     */
    public void saveDraft(@NonNull ConversationDraft draft,
            @NonNull Consumer<Result<Integer>> receiver) {
        String conversationId = draft.conversationId;
        draftLane.execute(conversationId, () -> {
            Result<Integer> result = gateway.callOnCurrentThread(agent -> {
                ConversationManager manager = agent.getConversationManager();
                if (manager == null) return null;
                return manager.saveDraft(draft);
            });
            draftLane.deliver(result, receiver);
        });
    }

    public void discardDraft(@NonNull String conversationId, @NonNull String draftInstanceId,
            long revision, @NonNull Consumer<Result<Boolean>> receiver) {
        draftLane.execute(conversationId, () -> {
            Result<Boolean> result = gateway.callOnCurrentThread(agent -> {
                ConversationManager manager = agent.getConversationManager();
                if (manager == null) return null;
                manager.discardDraft(conversationId, draftInstanceId, revision);
                return Boolean.TRUE;
            });
            draftLane.deliver(result, receiver);
        });
    }

    // ---------------------------------------------------------------- 受控附件（I6）

    /** Host 摄取附件（PFD staging）→ 返回 chip 元数据（正文不跨 Binder）。 */
    public void stageAttachment(@NonNull String conversationId,
            @NonNull android.os.ParcelFileDescriptor fd, @NonNull String declaredMime,
            @NonNull String displayName, @NonNull String clientOperationId,
            @NonNull Consumer<Result<com.matrix.agent.api.conversation.ConversationAttachment>> receiver) {
        gateway.execute(agent -> {
            com.matrix.agent.client.AttachmentManager manager = agent.getAttachmentManager();
            return manager == null ? null : manager.stage(conversationId, fd,
                    declaredMime, displayName, clientOperationId);
        }, receiver);
    }

    /** 当前会话的草稿附件（重进页面恢复 chips）。 */
    public void listDraftAttachments(@NonNull String conversationId,
            @NonNull Consumer<Result<List<com.matrix.agent.api.conversation.ConversationAttachment>>> receiver) {
        gateway.execute(agent -> {
            com.matrix.agent.client.AttachmentManager manager = agent.getAttachmentManager();
            return manager == null ? java.util.Collections
                    .<com.matrix.agent.api.conversation.ConversationAttachment>emptyList()
                    : manager.listDraftAttachments(conversationId);
        }, receiver);
    }

    public void deleteAttachment(@NonNull String attachmentId,
            @NonNull Consumer<Result<Integer>> receiver) {
        gateway.execute(agent -> {
            com.matrix.agent.client.AttachmentManager manager = agent.getAttachmentManager();
            return manager == null ? null
                    : manager.deleteAttachment(attachmentId,
                            java.util.UUID.randomUUID().toString());
        }, receiver);
    }

    /** 模型胶囊（I5）：当前 Host 运行时状态（provider/model/backend/ready）。 */
    public void modelRuntimeStatus(
            @NonNull Consumer<Result<com.matrix.agent.api.model.ModelRuntimeStatus>> receiver) {
        gateway.execute(agent -> {
            com.matrix.agent.client.ModelManager manager = agent.getModelManager();
            return manager == null ? null : manager.getRuntimeStatus();
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

    public void renameConversation(@NonNull String conversationId, @NonNull String title,
            @NonNull Consumer<Result<ConversationOperationResult>> receiver) {
        gateway.execute(agent -> {
            com.matrix.agent.client.ConversationManager manager =
                    agent.getConversationManager();
            return manager == null ? null
                    : manager.renameConversation(conversationId, title,
                            java.util.UUID.randomUUID().toString());
        }, receiver);
    }

    public void annotateMessage(@NonNull String conversationId, @NonNull String messageId,
            boolean favorite, String userNote,
            @NonNull Consumer<Result<ConversationOperationResult>> receiver) {
        gateway.execute(agent -> {
            com.matrix.agent.client.ConversationManager manager =
                    agent.getConversationManager();
            return manager == null ? null
                    : manager.annotateMessage(conversationId, messageId, favorite,
                            userNote, java.util.UUID.randomUUID().toString());
        }, receiver);
    }

    public void forkConversation(@NonNull String parentConversationId, long atSequenceNo,
            @NonNull Consumer<Result<String>> receiver) {
        gateway.execute(agent -> {
            com.matrix.agent.client.ConversationManager manager =
                    agent.getConversationManager();
            return manager == null ? null
                    : manager.forkConversation(parentConversationId, atSequenceNo,
                            java.util.UUID.randomUUID().toString());
        }, receiver);
    }

    public void getLineageSummary(@NonNull String conversationId,
            @NonNull Consumer<Result<String>> receiver) {
        gateway.execute(agent -> {
            com.matrix.agent.client.ConversationManager manager =
                    agent.getConversationManager();
            return manager == null ? null
                    : manager.getLineageSummary(conversationId);
        }, receiver);
    }

    public void speakAssistantMessage(@NonNull String conversationId,
            @NonNull String assistantMessageId,
            @NonNull Consumer<Result<ConversationOperationResult>> receiver) {
        gateway.execute(agent -> {
            com.matrix.agent.client.ConversationManager manager =
                    agent.getConversationManager();
            return manager == null ? null
                    : manager.speakAssistantMessage(conversationId, assistantMessageId,
                            java.util.UUID.randomUUID().toString());
        }, receiver);
    }

    public void wouldSummarizeOnNextRound(@NonNull String conversationId,
            @NonNull Consumer<Result<Boolean>> receiver) {
        gateway.execute(agent -> {
            com.matrix.agent.client.ConversationManager manager =
                    agent.getConversationManager();
            return manager == null ? null
                    : manager.wouldSummarizeOnNextRound(conversationId);
        }, receiver);
    }

    public void sendQuotedText(@NonNull String conversationId, @NonNull String text,
            @Nullable String quotedMessageId,
            @NonNull Consumer<Result<ConversationSubmission>> receiver) {
        gateway.execute(agent -> {
            com.matrix.agent.client.ConversationManager manager =
                    agent.getConversationManager();
            return manager == null ? null : manager.sendText(
                    new com.matrix.agent.api.conversation.SendTextRequest(
                            conversationId, text,
                            java.util.Locale.getDefault().toLanguageTag(),
                            quotedMessageId),
                    java.util.UUID.randomUUID().toString());
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
                        @Override public void onConversationInfoChanged(ConversationInfo info) {
                            listener.onConversationInfoChanged(info);
                        }
                        @Override public void onTransientTranscript(String conversationId,
                                String voiceSessionId, String text, boolean isFinal) {
                            listener.onTransientTranscript(conversationId, voiceSessionId, text,
                                    isFinal);
                        }
                        @Override public void onRuntimeStageChanged(
                                ConversationRuntimeStage stage) {
                            listener.onRuntimeStageChanged(stage);
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

    /**
     * 订阅 Host 侧已净化的调试轨迹。两端均要开 Gradle 标志才会有任何数据；release
     * 直接返回 no-op，避免误把 Binder 服务存在等同于有权显示内部诊断。
     */
    public AutoCloseable subscribeDebug(@NonNull DebugTraceListener listener,
            @NonNull Consumer<Result<AutoCloseable>> receiver) {
        if (!BuildConfig.MATRIX_DEBUG_TRACE_UI) {
            receiver.accept(Result.success(() -> { }));
            return () -> { };
        }
        final AutoCloseable[] handle = new AutoCloseable[1];
        final AtomicBoolean closed = new AtomicBoolean();
        gateway.execute(agent -> {
            DebugTraceManager manager = agent.getDebugTraceManager();
            if (manager == null) return null;
            handle[0] = manager.subscribe(listener::onDebugTrace);
            // 页面在 SDK worker 排队期间退出时，不能等 callback 回来后留下幽灵 Binder
            // 订阅；这是跨进程 one-way 通道最常见的生命周期泄漏点。
            if (closed.get()) {
                try {
                    handle[0].close();
                } catch (Exception ignored) {
                }
            }
            return handle[0];
        }, receiver);
        return () -> {
            closed.set(true);
            try {
                if (handle[0] != null) handle[0].close();
            } catch (Exception ignored) {
                // Binder death / 页面销毁均可安全退订。
            }
        };
    }

    /** 重进会话后的历史恢复；只有 matrix.debugTraceUi=true 才实际触发 IPC。 */
    public void loadDebugHistory(@NonNull String hostUserMessageId,
            @NonNull Consumer<Result<List<DebugTraceWireEvent>>> receiver) {
        if (!BuildConfig.MATRIX_DEBUG_TRACE_UI) {
            receiver.accept(Result.success(List.of()));
            return;
        }
        gateway.execute(agent -> {
            DebugTraceManager manager = agent.getDebugTraceManager();
            return manager == null ? List.<DebugTraceWireEvent>of()
                    : manager.getHistory(hostUserMessageId, 1_000);
        }, receiver);
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

    /** PTT 取消（ACTION_CANCEL）：丢弃 pendingFinal，本轮不产生任何消息。 */
    public void cancelRecording(@NonNull String sessionId,
            @NonNull String clientOperationId,
            @NonNull Consumer<Result<com.matrix.agent.api.voice.VoiceOperationResult>> receiver) {
        gateway.execute(agent -> {
            var manager = agent.getVoiceManager();
            if (manager == null) return null;
            return manager.stopSession(sessionId, clientOperationId);
        }, receiver);
    }
}
