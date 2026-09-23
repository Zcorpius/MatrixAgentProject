package com.matrix.agent.host.di;

import android.util.Log;

import com.matrix.agent.conversation.ConversationCoordinator;
import com.matrix.agent.conversation.ConversationVoiceBindingStore;
import com.matrix.agent.conversation.ConversationIds;
import com.matrix.agent.conversation.ConversationRecoveryCoordinator;
import com.matrix.agent.conversation.ConversationServiceGate;
import com.matrix.agent.conversation.ConversationReadbackService;
import com.matrix.agent.conversation.ConversationSummaryMarker;
import com.matrix.agent.conversation.ConversationTitleService;
import com.matrix.agent.contract.LlmClient;
import com.matrix.agent.contract.ModelConfig;

import java.util.function.Supplier;
import com.matrix.agent.conversation.ConversationStore;
import com.matrix.agent.conversation.persistence.RoomConversationStore;
import com.matrix.agent.voice.VoiceConversationBridge;
import com.matrix.agent.voice.VoiceRuntime;
import com.matrix.agent.voice.VoiceRuntimeHolder;
import com.matrix.agent.voice.VoiceSessionController;
import com.matrix.agent.data.db.MatrixDatabase;
import com.matrix.agent.host.rpc.ConversationServiceStub;
import com.matrix.agent.host.rpc.ModelServiceStub;
import com.matrix.agent.platform.KeyedSerialDispatcher;
import com.matrix.agent.task.AgentRuntimeRepository;
import com.matrix.agent.task.conversation.ConversationTaskSubmitter;
import com.matrix.agent.task.durable.PersistenceGate;

import androidx.annotation.Nullable;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 对话域组合根（阶段 A）。装配顺序即依赖顺序：
 * Store(事务投影) → Coordinator(keyed 派发) → Stub(Binder 门面)；
 * Submitter/HistorySource 由 AppContainer 经 task 域装配后注入。
 *
 * <p>恢复对账在 db 线程异步执行（Room 禁止主线程事务）；完成前 gate 保持关闭，
 * 全部 Binder 方法 fail-closed（对齐 TaskGraph 模式）。SQLCipher 降级（database=null）
 * 时对话域整体不装配，featureFlags 不通告该位。</p>
 */
final class ConversationGraph {
    private static final String TAG = "MatrixAgent";
    /** keyed 派发总待执行上限；超出即 OVERLOADED（§4.3：有界队列，拒绝优于烧预算）。 */
    private static final int DISPATCHER_MAX_PENDING = 16;

    private final ConversationCoordinator coordinator;
    /** 恢复对账的标题触发（降级装配为 null）。 */
    private final ConversationCoordinator.TerminalRoundSink recoveryTitleSink;
    /** 助手回复朗读（评估 v1.0 §4.8；降级装配为 null）。 */
    private final ConversationReadbackService readback;
    /** 摘要续聊标记（评估 v1.0 §4.6；降级装配为 null）。 */
    private final ConversationSummaryMarker summaryMarker;
    private final ConversationVoiceBindingStore bindingStore;
    private final VoiceConversationBridge voiceBridge;
    private final ConversationServiceStub service;
    private final ConversationServiceGate gate;
    private final AtomicBoolean recoveryAttempted = new AtomicBoolean(false);
    /** 附件域（I6）：staging store + 附件 Binder Stub（database=null 时为 null）。 */
    private final com.matrix.agent.attachment.RoomAttachmentStagingStore attachmentStore;
    private final com.matrix.agent.host.rpc.ConversationAttachmentServiceStub attachmentService;

    ConversationGraph(@Nullable android.content.Context appContext,
            @Nullable MatrixDatabase database,
            AgentRuntimeRepository runtime,
            ConversationTaskSubmitter submitter,
            com.matrix.agent.task.AgentBudget sharedBudget,
            ExecutorService conversationLane,
            ExecutorService databaseExecutor,
            PersistenceGate persistence,
            ModelServiceStub.CallerResolver callers,
            LlmClient titleModelClient,
            Supplier<ModelConfig> titleConfigSupplier,
            com.matrix.agent.conversation.ConversationRuntimeStageRegistry progressRegistry,
            com.matrix.agent.conversation.ConversationTaskProgressBridge progressBridge,
            okhttp3.OkHttpClient voiceCloudClient,
            ExecutorService voiceOutputExecutor,
            com.matrix.agent.model.SecureModelConfigStore modelConfigStore,
            ExecutorService attachmentIoExecutor) {
        if (database == null) {
            // 降级模式：不装配任何组件，gate 永不 open，featureFlags 不通告该位。
            this.coordinator = null;
            this.service = null;
            this.bindingStore = null;
            this.voiceBridge = null;
            this.recoveryTitleSink = null;
            this.readback = null;
            this.summaryMarker = null;
            this.gate = new ConversationServiceGate();
            this.attachmentStore = null;
            this.attachmentService = null;
            return;
        }
        this.gate = new ConversationServiceGate();
        // 草稿两表（I4）：提交受理事务经 DraftConsumer 同事务消费当前草稿。
        com.matrix.agent.conversation.persistence.RoomConversationDraftStore draftStore =
                new com.matrix.agent.conversation.persistence.RoomConversationDraftStore(
                        database.conversationDraftDao(), database::runInTransaction);
        // 附件域（I6 §9.2）：正文与元数据全驻 SQLCipher；Coordinator 端口负责提交验证
        // 与受限文本投影（ModelSanitizer 同配置），冻结在受理事务内由 store 完成。
        this.attachmentStore = new com.matrix.agent.attachment.RoomAttachmentStagingStore(
                database.conversationAttachmentDao(), database::runInTransaction);
        ConversationStore store = new RoomConversationStore(database, database::runInTransaction,
                draftStore::consumeSubmittedInCallerTransaction,
                database.conversationAttachmentDao(),
                // ModelExecutionSnapshot（I5 §8.2）：受理事务内取当前配置投影。
                () -> com.matrix.agent.model.ModelExecutionSnapshot.of(
                        modelConfigStore.load(),
                        modelConfigStore.configurationGeneration(),
                        modelConfigStore.configFingerprint()));
        KeyedSerialDispatcher dispatcher = new KeyedSerialDispatcher("matrix-conversation",
                conversationLane, DISPATCHER_MAX_PENDING);
        this.coordinator = new ConversationCoordinator(store, submitter, runtime::executePrepared,
                dispatcher,
                conversationId -> ConversationIds.agentSessionId(conversationId,
                        "DRIVER", "DRIVER"),
                runtime::offerSteer);
        this.coordinator.setAttachmentPort(new com.matrix.agent.attachment
                .ConversationAttachmentPort(database.conversationAttachmentDao(),
                new com.matrix.agent.attachment.AttachmentContextProjector(
                        sharedBudget.getMaxMessageChars())));
        this.attachmentService = new com.matrix.agent.host.rpc
                .ConversationAttachmentServiceStub(attachmentStore, store, persistence,
                callers, attachmentIoExecutor);
        // 运行阶段追踪（I3）：bind 映射 + QUEUED/清理生命周期挂在协调器上。
        this.coordinator.setProgressTracking(progressRegistry, progressBridge);
        // 自动标题（评估 v1.0 §4.1）：功能型轻量调用——同一 LlmClient + 配置 supplier，
        // 单线程低优先级，比较交换写入；协调器与恢复对账两路终态都汇入同一服务。
        ConversationTitleService titleService = new ConversationTitleService(store,
                titleModelClient, titleConfigSupplier);
        this.coordinator.setTerminalRoundSink(titleService::onTerminalRound);
        this.recoveryTitleSink = titleService::onTerminalRound;
        this.bindingStore = new ConversationVoiceBindingStore();
        runtime.addConversationClearHook(bindingStore::clearAll);
        // clearUserData 覆盖（I4/I3）：草稿与 tombstone、内存运行阶段随清数据一并回收。
        runtime.addConversationClearHook(() -> draftStore.clearForUsers(
                java.util.Arrays.asList(com.matrix.agent.identity.ActorUsers.USER_DRIVER,
                        com.matrix.agent.identity.ActorUsers.USER_PASSENGER)));
        runtime.addConversationClearHook(progressRegistry::clearAll);
        // 附件（I6）：clearUserData 级联删除附件正文与元数据（owner 维度）。
        runtime.addConversationClearHook(() -> attachmentStore.clearForUsers(
                java.util.Arrays.asList(com.matrix.agent.identity.ActorUsers.USER_DRIVER,
                        com.matrix.agent.identity.ActorUsers.USER_PASSENGER)));
        com.matrix.agent.voice.tencent.TencentCloudTtsRouteFactory readbackOutputRoute =
                new com.matrix.agent.voice.tencent.TencentCloudTtsRouteFactory(
                        (android.app.Application) appContext.getApplicationContext(), voiceCloudClient);
        this.readback = new ConversationReadbackService(store,
                () -> readbackOutputRoute.create(
                        () -> new com.matrix.agent.voice.platform.AndroidTtsAdapter(
                                (android.app.Application) appContext.getApplicationContext()),
                        voiceOutputExecutor),
                new com.matrix.agent.voice.platform.AndroidAudioFocusAdapter(
                        appContext.getApplicationContext()),
                () -> {
                    com.matrix.agent.voice.VoiceRuntime voice =
                            com.matrix.agent.voice.VoiceRuntimeHolder.get();
                    return voice != null && voice.isSessionActive();
                });
        // 摘要标记（评估 v1.0 §4.6）：与压缩器同源 80% 判定，读时重算不落库
        this.summaryMarker = new ConversationSummaryMarker(
                new com.matrix.agent.conversation.ConversationHistoryAdapter(store),
                sharedBudget);
        this.service = new ConversationServiceStub(coordinator, gate, persistence, callers,
                bindingStore, readback, summaryMarker, draftStore, progressRegistry,
                attachmentStore);
        // 标题落库属于独立的低优先级功能调用；通过既有会话订阅只推展示元数据，
        // 让当前页面无需重进或轮询就能从默认标题切到 AUTO 标题。
        titleService.setTitleChangedSink(service::onConversationInfoChanged);
        // 阶段 C：创建对话桥；配置器由 MatrixServiceGraph 注册到 VoiceRuntime，保证当前
        // 与未来（引擎切换后重建）的 Controller 都会接到同一套治理。
        this.voiceBridge = createVoiceBridge();
        recoverOffMainThread(databaseExecutor, store, draftStore, attachmentStore);
    }

    boolean isAvailable() {
        return coordinator != null;
    }

    /** 附件域 Binder（database=null 时为 null，ServiceGraph 不通告特性位）。 */
    android.os.IBinder attachmentBinder() {
        return attachmentService == null ? null : attachmentService.asBinder();
    }

    ConversationVoiceBindingStore bindingStore() { return bindingStore; }

    VoiceConversationBridge voiceBridge() { return voiceBridge; }

    /** Voice configuration changed while no session is active: the next readback uses its route. */
    void refreshReadbackOutputRoute() {
        if (readback != null) readback.refreshOutputRoute();
    }

    /**
     * 创建对话桥——提交侧走 Coordinator.submitText，
     * 回注侧经 Controller 的 TerminalListener 复用唯一 TTS 治理。
     */
    private VoiceConversationBridge createVoiceBridge() {
        return new VoiceConversationBridge(coordinator,
                new VoiceConversationBridge.ReceiptListener() {
                    @Override
                    public void onSubmissionAccepted(
                            VoiceConversationBridge.VoiceResponseToken token) {
                        VoiceSessionController controller = activeController();
                        if (controller != null) {
                            controller.onSubmissionAccepted(token);
                        }
                    }

                    @Override
                    public void onSubmissionFailed(
                            VoiceConversationBridge.VoiceResponseToken token,
                            String errorCode) {
                        VoiceSessionController controller = activeController();
                        if (controller != null) {
                            controller.onSubmissionFailed(token, errorCode);
                        }
                    }
                },
                new VoiceConversationBridge.TerminalListener() {
                    @Override
                    public void onConversationTerminal(
                            VoiceConversationBridge.VoiceResponseToken token,
                            com.matrix.agent.task.AgentOutcome outcome) {
                        VoiceSessionController controller = activeController();
                        if (controller != null) {
                            controller.onConversationTerminal(token, outcome);
                        }
                    }
                },
                "conv:"); // session prefix: conv:<id>:DRIVER:DRIVER 格式由 Coordinator 侧拼
    }

    /** 由 VoiceRuntime 在每次 controller 发布前调用；不持有 runtime，避免双权威。 */
    java.util.function.Consumer<VoiceSessionController> controllerConfigurer() {
        return controller -> {
        controller.setConversationBridge(voiceBridge);
        // 预滚缓冲：KWS→ASR 交接修丢首字（§7.3/阶段 C-3）
        controller.setPrerollBuffer(new com.matrix.agent.voice.AudioPrerollBuffer(2500));
        // 唤醒路由器：wake final 无绑定时决定投递线程（§6.3/阶段 C-2）
        controller.setWakeRouter(new com.matrix.agent.voice.WakeConversationRouter(
                coordinator, "demo-driver", "DRIVER"));
        android.util.Log.i(TAG, "[ConversationGraph] 对话桥+预滚+唤醒路由已注入");
        };
    }

    private VoiceSessionController activeController() {
        VoiceRuntime runtime = VoiceRuntimeHolder.get();
        return runtime == null ? null : runtime.getController();
    }

    android.os.IBinder binder() {
        return service == null ? null : service.asBinder();
    }

    void shutdown() {
        if (readback != null) {
            readback.close();
        }
        if (service != null) {
            service.shutdown();
        }
    }

    private void recoverOffMainThread(ExecutorService databaseExecutor,
            ConversationStore store,
            com.matrix.agent.conversation.persistence.RoomConversationDraftStore draftStore,
            com.matrix.agent.attachment.RoomAttachmentStagingStore attachmentStore) {
        try {
            databaseExecutor.execute(() -> {
                if (!recoveryAttempted.compareAndSet(false, true)) {
                    return;
                }
                try {
                    int recovered = new ConversationRecoveryCoordinator(store,
                            recoveryTitleSink).recover();
                    gate.open();
                    Log.i(TAG, "[ConversationGraph] 恢复对账完成 interrupted=" + recovered);
                } catch (RuntimeException failure) {
                    // 对账失败保持关门——宁可拒绝服务，不冒“半套对话状态 +
                    // 幂等账本缺口”的风险；重启 Host 后重试。
                    Log.e(TAG, "[ConversationGraph] 恢复对账失败，对话域保持关闭", failure);
                }
                // tombstone 清理（I4 §7.2）：恢复完成后 best-effort 执行一次；
                // 失败只影响存储上界，不影响任何功能路径。
                try {
                    draftStore.cleanupTombstones(System.currentTimeMillis());
                } catch (RuntimeException cleanupFailure) {
                    Log.w(TAG, "[ConversationGraph] tombstone 清理失败", cleanupFailure);
                }
                // 草稿附件 GC（I6 §9.2）：超期未提交即回收，best-effort。
                try {
                    attachmentStore.cleanupExpiredDrafts(System.currentTimeMillis());
                } catch (RuntimeException cleanupFailure) {
                    Log.w(TAG, "[ConversationGraph] 附件 GC 失败", cleanupFailure);
                }
            });
        } catch (RejectedExecutionException unavailable) {
            Log.e(TAG, "[ConversationGraph] 恢复任务被拒绝，对话域保持关闭", unavailable);
        }
    }
}
