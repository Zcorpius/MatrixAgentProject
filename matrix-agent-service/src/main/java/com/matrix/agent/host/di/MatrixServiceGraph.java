package com.matrix.agent.host.di;

import com.matrix.agent.host.rpc.ModelServiceStub;
import com.matrix.agent.task.durable.PersistenceGate;
import com.matrix.agent.task.durable.PersistentTaskManager;

import android.app.Application;
import android.os.IBinder;

import com.matrix.agent.api.common.MatrixServiceConstants;

/** Owns Host domain graphs; Android Service only routes Binder calls and lifecycle. */
public final class MatrixServiceGraph {
    private final PersistenceGate persistence;
    private final TaskGraph tasks;
    private final ModelGraph model;
    private final DownloadGraph download;
    private final VoiceGraph voice;
    private final ConversationGraph conversation;

    public MatrixServiceGraph(AppContainer container, ModelServiceStub.CallerResolver callers) {
        persistence = new PersistenceGate(container.getMatrixDatabase());
        tasks = new TaskGraph(container.getMatrixDatabase(),
                container.getAgentRuntimeRepository(),
                container.getExecutorRegistry().hostDispatcherExecutor(),
                container.getExecutorRegistry().dbExecutor());
        model = new ModelGraph(container, persistence, callers);
        download = new DownloadGraph(container, persistence, callers);
        voice = new VoiceGraph((Application) container.getAppContext(), container.getModelDownloadDao(),
                container.getExecutorRegistry().voiceDownloadExecutor(), persistence, callers);
        conversation = new ConversationGraph(container.getAppContext(),
                container.getMatrixDatabase(),
                container.getAgentRuntimeRepository(),
                container.getConversationTaskSubmitter(),
                container.getSharedBudget(),
                container.getExecutorRegistry().conversationExecutor(),
                container.getExecutorRegistry().dbExecutor(), persistence, callers,
                container.getTitleModelClient(), container.getTitleConfigSupplier(),
                container.getConversationProgressRegistry(),
                container.getConversationProgressBridge(), container.getHttpClient().provider(),
                container.getExecutorRegistry().networkExecutor(),
                container.getModelConfigStore(),
                container.getExecutorRegistry().networkExecutor());
        if (conversation.isAvailable()) {
            voice.setBindingStore(conversation.bindingStore());
            voice.addControllerConfigurer(conversation.controllerConfigurer());
            voice.setTtsOutputRouteChangedListener(conversation::refreshReadbackOutputRoute);
        }
    }

    public boolean persistenceAvailable() { return persistence.isAvailable(); }
    public boolean tasksAvailable() { return tasks.isAvailable(); }
    public String persistenceUnavailableReason() { return persistence.unavailableReason(); }
    public PersistentTaskManager tasks() { return tasks.requireManager(); }
    public IBinder modelBinder() { return model.binder(); }
    public IBinder downloadBinder() { return download.binder(); }
    public IBinder voiceBinder() { return voice.binder(); }
    public IBinder conversationBinder() { return conversation.binder(); }
    public IBinder attachmentBinder() { return conversation.attachmentBinder(); }
    public IBinder debugTraceBinder() { return debugTraceStub; }

    private final com.matrix.agent.host.rpc.DebugTraceServiceStub debugTraceStub =
            new com.matrix.agent.host.rpc.DebugTraceServiceStub(
                    com.matrix.agent.debugtrace.DebugTraceHolder.get(),
                    com.matrix.agent.BuildConfig.MATRIX_DEBUG_TRACE_UI);
    public int featureFlags() {
        int flags = MatrixServiceConstants.FEATURE_DURABLE_TASKS
                | MatrixServiceConstants.FEATURE_MODEL_DOMAIN
                | MatrixServiceConstants.FEATURE_DOWNLOAD_DOMAIN
                | MatrixServiceConstants.FEATURE_PERSISTENCE_GATE;
        if (voice.isAvailable()) flags |= MatrixServiceConstants.FEATURE_VOICE_DOMAIN;
        if (conversation.isAvailable()) {
            flags |= MatrixServiceConstants.FEATURE_CONVERSATION_DOMAIN;
            // 附件 staging 与对话域同库（SQLCipher 可用才装配 ConversationGraph）；
            // 客户端按位隐藏 `+` 入口，而不是调用后吃异常。
            flags |= MatrixServiceConstants.FEATURE_ATTACHMENT_DOMAIN;
        }
        return flags;
    }
    public void shutdown() {
        voice.shutdown();
        conversation.shutdown();
        tasks.shutdown();
    }
    public boolean conversationAvailable() { return conversation.isAvailable(); }
}
