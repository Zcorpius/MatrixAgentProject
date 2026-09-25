package com.matrix.agent.host.di;
import com.matrix.agent.platform.MatrixExecutorRegistry;

import com.matrix.agent.identity.AgentRequest;

import android.content.Context;
import android.util.Log;

import com.matrix.agent.task.AgentBudget;
import com.matrix.agent.platform.AuditDigest;
import com.matrix.agent.task.ModelCallExecutor;
import com.matrix.agent.task.steer.Steer;
import com.matrix.agent.task.steer.SteerMailbox;
import com.matrix.agent.task.steer.StaleSteerHandler;
import com.matrix.agent.task.scheduler.TaskScheduler;
import com.matrix.agent.task.token.CharFallbackTokenizer;
import com.matrix.agent.task.token.JtokkitTokenizer;
import com.matrix.agent.task.token.Tokenizer;
import com.matrix.agent.task.capability.CapabilityRegistry;
import com.matrix.agent.task.capability.CapabilityProvider;
import com.matrix.agent.vehicle.DefaultVehicleStateSource;
import com.matrix.agent.identity.ActorUsers;
import com.matrix.agent.intent.IntentClassifier;
import com.matrix.agent.intent.KeywordMemoryIntentDetector;
import com.matrix.agent.data.memory.MemoryRecaller;
import com.matrix.agent.task.policy.PolicyEngine;
import com.matrix.agent.session.SessionLockManager;
import com.matrix.agent.session.SessionManager;
import com.matrix.agent.demo.MockCapabilityProvider;
import com.matrix.agent.conversation.ConversationHistoryAdapter;
import com.matrix.agent.task.conversation.ConversationContextAssembler;
import com.matrix.agent.task.conversation.ConversationHistorySource;
import com.matrix.agent.task.conversation.ConversationTaskSubmitter;
import com.matrix.agent.platform.control.AndroidSystemControlAdapter;
import com.matrix.agent.platform.control.SystemControlCapabilityProvider;
import com.matrix.agent.task.capability.RoutedCapabilityProvider;
import com.matrix.agent.task.capability.MediaCapabilities;
import com.matrix.agent.platform.media.AndroidAppLaunchPort;
import com.matrix.agent.platform.media.AndroidMediaSessionPort;
import com.matrix.agent.platform.media.AndroidPackageProbe;
import com.matrix.agent.platform.media.AndroidQQMusicUiPort;
import com.matrix.agent.platform.media.MediaCapabilityProvider;
import com.matrix.agent.platform.media.MediaAvailabilityCache;
import com.matrix.agent.task.tool.ToolExecutor;
import com.matrix.agent.data.memory.MemoryStore;
import com.matrix.agent.task.AgentRuntimeRepository;
import com.matrix.agent.contract.GatewayLifecycleManager;
import com.matrix.agent.model.ModelGatewayRepository;
import com.matrix.agent.data.audit.AuditEventRecorder;
import com.matrix.agent.data.audit.AuditRepository;
import com.matrix.agent.data.db.MatrixDatabase;
import com.matrix.agent.data.db.ModelDownloadDao;
import com.matrix.agent.download.ModelDownloadManager;
import com.matrix.agent.download.ModelDownloadWorkScheduler;
import com.matrix.agent.download.DownloadRuntime;
import com.matrix.agent.model.ModelApiClient;
import com.matrix.agent.model.SecureModelConfigStore;
import com.matrix.agent.platform.MatrixHttpClient;

/** Explicit composition root for replaceable runtime and platform dependencies. */
public final class AppContainer implements DownloadRuntime {
    private static final String TAG = "MatrixAgent";
    private final AgentRuntimeRepository agentRuntimeRepository;
    private final ModelGatewayRepository modelGatewayRepository;
    private final SteerMailbox steerMailbox;

    private final DefaultVehicleStateSource vehicleStateSource;
    private final MediaAvailabilityCache mediaAvailability;
    /**
     * 统一 shutdown 入口持有的资源。
     *
     * <p>旧实现 schedulerPool / ioPool 是构造器局部变量,Application.onTerminate 只关 scheduler;
     * ioPool / auditEventRecorder 没人关,daemon Thread 在进程死亡时虽被回收,但模拟器/集成测试
     * /AAOS Service 重建场景下残留 worker 不可控。改为 final field + {@link #shutdown()} 统一关闭。
     */
    private final MatrixExecutorRegistry executorRegistry;
    /** SQLCipher/database/download subgraph; separates persistence ownership from runtime wiring. */
    private final PersistenceRuntimeGraph persistenceGraph;
    private final MatrixDatabase activeDatabase;
    private final DownloadRuntimeGraph downloadGraph;
    private final MatrixHttpClient httpClient;
    /**
     * 端侧 gateway lifecycle 持有——AppContainer.shutdown 统一关闭其 drain executor。
     */
    private final GatewayLifecycleManager gatewayLifecycleManager;
    /** 对话域 task 端口（纯准备：分类快照 + 种子装配）；database=null 时为 null。 */
    private final ConversationTaskSubmitter conversationTaskSubmitter;
    /** clearUserData 覆盖对话表（§5.2）；database=null 时为 null。 */
    private final Runnable conversationClearHook;
    private final com.matrix.agent.data.audit.AuditEventRecorder auditEventRecorderField;
    /** Application Context used by process-scoped service adapters and download storage. */
    private final Context appContext;
    /**
     * 端侧模型下载：ModelDownloadManager + DAO。database=null（SQLCipher/KeyStore 不可用）
     * 的降级路径下二者均为 null——下载功能依赖 DAO 落进度，Binder/Service 显式返回
     * PERSISTENCE_UNAVAILABLE，不静默失败。
     */

    public AppContainer(Context context) {
        long started = System.nanoTime();
        Log.i(TAG, "[App] AppContainer init start");
        Context appContext = context.getApplicationContext();
        this.appContext = appContext;
        // Construct the sole owner of process-long-lived workers before any component can submit.
        // Request scheduling and model/tool I/O are different bounded pools, so nested waits remain
        // deadlock-free while every production worker has one lifecycle owner.
        this.executorRegistry = new MatrixExecutorRegistry();
        this.httpClient = new MatrixHttpClient();
        CapabilityRegistry registry = CapabilityRegistry.createRuntimeRegistry();
        PolicyEngine policyEngine = new PolicyEngine(registry);
        SessionManager sessionManager = new SessionManager();
        SessionLockManager sessionLockManager = new SessionLockManager();
        // Task execution and model/tool I/O must remain physically separate: scheduler workers
        // wait on I/O futures, therefore sharing one bounded pool could deadlock under load.
        ToolExecutor toolExecutor = new ToolExecutor(4, executorRegistry.networkExecutor());
        ModelCallExecutor modelCallExecutor = new ModelCallExecutor(2,
                executorRegistry.networkExecutor(), executorRegistry.modelExecutor());
        steerMailbox = new SteerMailbox();
        // 车辆运动状态源；后续 OEM 版本在此替换为 CarPropertyManager-backed 实现。
        vehicleStateSource = new DefaultVehicleStateSource();
        // MatrixDatabase 提前装配——audit + memory 共用同一 SQLCipher 实例
        // (getInstance 单例,keyProvider 失败 → database=null → audit/memory 均进入显式降级)。
        this.persistenceGraph = new PersistenceRuntimeGraph(appContext);
        MatrixDatabase encryptedDatabase = PendingUserDataReset.databaseAfterRecovery(
                appContext, persistenceGraph.database(),
                executorRegistry.dbExecutor());
        // Memory graph owns the legacy migration plus the encrypted/volatile fallback boundary.
        // It is assembled before models because prompt construction needs the recaller.
        MemoryRuntimeGraph memoryGraph = new MemoryRuntimeGraph(appContext, encryptedDatabase, sessionManager,
                executorRegistry.dbExecutor());
        MatrixDatabase database = memoryGraph.isDegraded() ? null : encryptedDatabase;
        this.activeDatabase = database;
        this.downloadGraph = new DownloadRuntimeGraph(appContext, database, executorRegistry.dbExecutor(),
                httpClient);
        MemoryStore memoryStore = memoryGraph.store();
        MemoryRecaller memoryRecaller = memoryGraph.recaller();
        com.matrix.agent.data.memory.MemoryWriter memoryWriter = memoryGraph.writer();
        if (memoryGraph.isDegraded()) {
            Log.w(TAG, "[App] volatile memory fallback active; persistent Binder writes stay gated");
        }
        CapabilityProvider domainProvider = new MockCapabilityProvider(memoryStore, memoryWriter);
        SystemControlCapabilityProvider systemControlProvider = new SystemControlCapabilityProvider(
                new AndroidSystemControlAdapter(appContext));
        AndroidAppLaunchPort mediaLauncher = new AndroidAppLaunchPort(appContext);
        MediaCapabilityProvider mediaProvider = new MediaCapabilityProvider(
                new AndroidPackageProbe(appContext), new AndroidMediaSessionPort(appContext),
                mediaLauncher, new AndroidQQMusicUiPort(appContext, mediaLauncher),
                new com.matrix.agent.platform.media.AndroidBilibiliUiPort(appContext,
                        mediaLauncher));
        mediaAvailability = new MediaAvailabilityCache(appContext,
                new AndroidPackageProbe(appContext), executorRegistry.networkExecutor());
        java.util.Map<String, CapabilityProvider> platformRoutes = new java.util.LinkedHashMap<>();
        platformRoutes.put(SystemControlCapabilityProvider.MEDIA_VOLUME, systemControlProvider);
        platformRoutes.put(SystemControlCapabilityProvider.SCREEN_BRIGHTNESS, systemControlProvider);
        for (String capability : MediaCapabilityProvider.capabilities()) {
            if (registry.find(capability) == null) {
                throw new IllegalStateException("媒体 Provider 能力未注册: " + capability);
            }
            platformRoutes.put(capability, mediaProvider);
        }
        for (String capability : registry.snapshot().keySet()) {
            if (capability.startsWith("media.") && !MediaCapabilities.ALL.contains(capability)) {
                throw new IllegalStateException("媒体能力缺少 Provider 路由: " + capability);
            }
        }
        CapabilityProvider provider = new RoutedCapabilityProvider(domainProvider, platformRoutes);
        ModelRuntimeGraph modelGraph = new ModelRuntimeGraph(appContext, memoryStore,
                memoryRecaller, executorRegistry.modelRetirementScheduler(), httpClient);
        modelGatewayRepository = modelGraph.repository();
        ModelApiClient modelClient = modelGraph.client();
        SecureModelConfigStore configStore = modelGraph.configStore();
        this.modelConfigStore = configStore;
        IntentClassifier appClassifier = modelGraph.intentClassifier();
        AuditRuntimeGraph auditGraph = new AuditRuntimeGraph(appContext, database,
                executorRegistry.timerScheduler());
        AuditRepository auditRepository = auditGraph.repository();

        // Tokenizer 装配——jtokkit O200K_BASE,接入 AgentEngine 主路径。
        // 失败时(Jtokkit 注册异常 / 词表缺失)退 CharFallbackTokenizer——
        // 已有 fallback 设计,JtokkitTokenizer 仅在测试/debug 用,切主路径。
        Tokenizer localTokenizer;
        try {
            localTokenizer = new JtokkitTokenizer();
            Log.i(TAG, "[App] Tokenizer init jtokkit=O200K_BASE available=true");
        } catch (Exception ex) {
            Log.w(TAG, "[App] Jtokkit init FAILED, fallback to CharFallback cause="
                    + ex.getClass().getSimpleName() + ": " + ex.getMessage(), ex);
            localTokenizer = CharFallbackTokenizer.INSTANCE;
        }
        Tokenizer tokenizer = localTokenizer;

        // 同一个 AgentBudget 同时驱动 AgentEngine(字符/迭代/Tool 上限)
        // 和 AgentRequest.timeoutMillis(总 deadline)。Repository 不再硬编码 60_000L。
        // 注入 SteerMailbox,启用运行时追加指令(REPROMPT/FORCE_TOOL/DEFER)。
        sharedBudget = new AgentBudget();
        // 主驾优先调度器正式接入 APK runtime。parallelism=2
        // 让主驾抢占 + 副驾排队可并行,SessionLockManager 仍保证同 session 串行。
        TaskScheduler scheduler = new TaskScheduler(2, sessionLockManager,
                executorRegistry.taskExecutor());
        AuditDigest auditDigest = auditGraph.digest();
        AuditEventRecorder auditEventRecorder = auditGraph.eventRecorder();
        this.auditEventRecorderField = auditEventRecorder;
        // SteerMailbox epoch gate——clearUserData 后,旧 Steer 在 drain 时
        // drop + audit(STEER_DROPPED_STALE)。staleHandler 在 auditEventRecorder 装配之后注入。
        steerMailbox.setStaleHandler(new SteerMailboxStaleHandler(auditEventRecorder));
        // 运行阶段追踪（输入交互增强 I3）：registry+bridge 先于 task/conversation 两域创建——
        // task 域经 TaskProgressSink 发布，conversation 域 bind/unbind 映射并投影。
        // SQLCipher 降级时 conversation 域不装配，事件自然无处投递（fail-safe）。
        com.matrix.agent.conversation.ConversationRuntimeStageRegistry progressRegistry =
                new com.matrix.agent.conversation.ConversationRuntimeStageRegistry();
        this.conversationProgressRegistry = progressRegistry;
        this.conversationProgressBridge = new com.matrix.agent.conversation
                .ConversationTaskProgressBridge(progressRegistry,
                com.matrix.agent.voice.CapabilitySpeechNames::friendlyName);
        // TaskRuntimeGraph 是 task 域唯一组合根：AppContainer 不再持有 Engine 的构造细节，
        // 只把跨域基础设施组装成依赖对象交给它。
        TaskRuntimeGraph.Dependencies taskDependencies = new TaskRuntimeGraph.Dependencies();
        taskDependencies.modelClient = modelClient;
        taskDependencies.configStore = configStore;
        taskDependencies.modelCallExecutor = modelCallExecutor;
        taskDependencies.policyEngine = policyEngine;
        taskDependencies.registry = registry;
        taskDependencies.provider = provider;
        taskDependencies.sessionManager = sessionManager;
        taskDependencies.sessionLockManager = sessionLockManager;
        taskDependencies.toolExecutor = toolExecutor;
        taskDependencies.budget = sharedBudget;
        taskDependencies.steerMailbox = steerMailbox;
        taskDependencies.auditRepository = auditRepository;
        taskDependencies.auditDigest = auditDigest;
        taskDependencies.tokenizer = tokenizer;
        taskDependencies.auditEventRecorder = auditEventRecorder;
        taskDependencies.memoryRecaller = memoryRecaller;
        taskDependencies.memoryWriter = memoryWriter;
        taskDependencies.scheduler = scheduler;
        taskDependencies.vehicleStateSource = vehicleStateSource;
        taskDependencies.runtimeProfileSource = new RuntimeProfileResolver(appContext);
        taskDependencies.memoryStore = memoryStore;
        taskDependencies.intentClassifier = appClassifier;
        taskDependencies.lifecycleExecutor = executorRegistry.lifecycleExecutor();
        taskDependencies.taskProgressSink = this.conversationProgressBridge;
        taskDependencies.context = appContext;
        taskDependencies.mediaAvailability = mediaAvailability;
        taskDependencies.pendingMediaConfirmation = mediaProvider;
        taskDependencies.pendingBilibiliSelection = mediaProvider;
        TaskRuntimeGraph taskRuntimeGraph = new TaskRuntimeGraph(taskDependencies);
        agentRuntimeRepository = taskRuntimeGraph.repository();
        agentRuntimeRepository.setLegacyMemoryClearHook(
                () -> com.matrix.agent.data.memory.RoomMemoryMigrator
                        .clearLegacySharedPreferences(appContext));
        agentRuntimeRepository.setResetLifecycleHooks(
                () -> PendingUserDataReset.mark(appContext),
                () -> PendingUserDataReset.clearMarker(appContext));
        gatewayLifecycleManager = taskRuntimeGraph.lifecycleManager();
        // 调试轨迹日志无条件开启。所有构建变体均只由显式 Gradle 属性
        // matrix.debugTraceUi 决定是否把已净化投影写入 SQLCipher 并允许 UI 订阅；
        // false 会主动清掉残留 trace 行，绝不把 buildType 变成隐藏的第二开关。
        boolean debugTraceUi = com.matrix.agent.BuildConfig.MATRIX_DEBUG_TRACE_UI;
        com.matrix.agent.debugtrace.DebugTraceStore debugTraceStore = null;
        if (database != null && debugTraceUi) {
            debugTraceStore = new com.matrix.agent.debugtrace.RoomDebugTraceStore(database,
                    executorRegistry.dbExecutor());
        } else if (database != null) {
            // 以当前构建配置为准 fail-closed：避免刷成 release 后遗留 internal 调试内容。
            new com.matrix.agent.debugtrace.RoomDebugTraceStore(database,
                    executorRegistry.dbExecutor()).clearAll();
        }
        com.matrix.agent.debugtrace.DebugTraceHolder.set(
                new com.matrix.agent.debugtrace.DebugTraceEmitter(debugTraceUi, debugTraceStore));

        // 功能型轻量调用端口（摘要/标题共用）：与任务主路径同一 ModelApiClient + 配置源
        titleModelClient = taskDependencies.modelClient;
        titleConfigSupplier = taskDependencies.configStore::load;

        // 对话域 task 端口装配：分类器/记忆意图与 TaskRequestFactory 同源实例，
        // 历史=已完成 user/assistant 投影；SQLCipher 降级时端口不装配（域整体关闭）。
        if (database == null) {
            conversationTaskSubmitter = null;
            conversationClearHook = null;
        } else {
            // 单一 Store 实例：历史投影与 clearUserData 钩子共用同一事务投影。
            com.matrix.agent.conversation.persistence.RoomConversationStore conversationStore =
                    new com.matrix.agent.conversation.persistence.RoomConversationStore(
                            database, database::runInTransaction);
            ConversationContextAssembler conversationAssembler =
                    new ConversationContextAssembler(sharedBudget,
                            new ConversationHistoryAdapter(conversationStore));
            conversationTaskSubmitter = new ConversationTaskSubmitter(
                    appClassifier, KeywordMemoryIntentDetector.INSTANCE, conversationAssembler);
            conversationClearHook = () -> {
                conversationStore.clearForUsers(ActorUsers.allKnownUserIds());
            };
            agentRuntimeRepository.setConversationClearHook(conversationClearHook);
        }

        modelGraph.applyInitialGateway(agentRuntimeRepository, executorRegistry.modelExecutor());
        Log.i(TAG, "[App] AppContainer init done capabilities=" + registry.toToolDefinitions().size()
                + " durationMs=" + ((System.nanoTime() - started) / 1_000_000L));
    }

    public AgentRuntimeRepository getAgentRuntimeRepository() { return agentRuntimeRepository; }
    public ModelGatewayRepository getModelGatewayRepository() { return modelGatewayRepository; }
    /** Application Context used only by service-domain adapters. */
    public Context getAppContext() { return appContext; }
    /** SQLCipher-backed database; null means the explicit persistence-degraded mode is active. */
    @androidx.annotation.Nullable
    public MatrixDatabase getMatrixDatabase() { return activeDatabase; }
    /** 端侧模型下载管理器（database=null 时为 null）。 */
    @androidx.annotation.Nullable
    public ModelDownloadManager getModelDownloadManager() { return downloadGraph.manager(); }
    /** 模型下载 DAO（database=null 时为 null）。 */
    @androidx.annotation.Nullable
    public ModelDownloadDao getModelDownloadDao() { return downloadGraph.dao(); }
    /** True only after the on-disk/DAO reconciliation transaction completes. */
    public boolean isDownloadRecoveryComplete() { return downloadGraph.isReady(); }
    @Override public ModelDownloadManager modelDownloadManager() { return getModelDownloadManager(); }
    @Override public ModelDownloadDao modelDownloadDao() { return getModelDownloadDao(); }
    @Override public java.util.concurrent.ExecutorService downloadExecutor() {
        return executorRegistry.networkExecutor();
    }
    @Override public java.util.concurrent.ScheduledExecutorService timerExecutor() {
        return executorRegistry.timerScheduler();
    }
    /** WorkManager admission scheduler; transfer execution remains in DownloadService. */
    public ModelDownloadWorkScheduler getModelDownloadWorkScheduler() {
        return downloadGraph.workScheduler();
    }

    /**
     * 统一关闭——Repository(取消在途)→ executor registry → auditEventRecorder。
     *
     * <p>旧实现只有 Repository.shutdown()(关 scheduler),ioPool + auditEventRecorder 没人关,
     * 模拟器/集成测试/AAOS Service 重建场景下残留 worker。改为统一入口,由
     * {@link MatrixAgentApplication#onTerminate()} 调用,真机依赖进程级回收兜底。
     */
    /** 对话域 task 端口；SQLCipher 降级时为 null（对话域不装配）。 */
    private com.matrix.agent.task.AgentBudget sharedBudget;
    private com.matrix.agent.contract.LlmClient titleModelClient;
    private java.util.function.Supplier<com.matrix.agent.contract.ModelConfig>
            titleConfigSupplier;
    /** 模型配置存储（I5：ModelExecutionSnapshot 的 generation/fingerprint 供应源）。 */
    private com.matrix.agent.model.SecureModelConfigStore modelConfigStore;
    /** 运行阶段追踪（I3）：先于 task/conversation 两域创建，MatrixServiceGraph 注入对话域。 */
    private final com.matrix.agent.conversation.ConversationRuntimeStageRegistry
            conversationProgressRegistry;
    private final com.matrix.agent.conversation.ConversationTaskProgressBridge
            conversationProgressBridge;

    /** 对话运行阶段注册表（订阅快照/清理）；SQLCipher 降级时事件无处投递但实例仍安全。 */
    public com.matrix.agent.conversation.ConversationRuntimeStageRegistry
            getConversationProgressRegistry() {
        return conversationProgressRegistry;
    }

    public com.matrix.agent.conversation.ConversationTaskProgressBridge
            getConversationProgressBridge() {
        return conversationProgressBridge;
    }

    public ConversationTaskSubmitter getConversationTaskSubmitter() {
        return conversationTaskSubmitter;
    }
    /** 对话域与任务域共用的预算（压缩器/摘要标记同源口径）。 */
    public com.matrix.agent.task.AgentBudget getSharedBudget() {
        return sharedBudget;
    }

    /** 自动标题的功能型轻量调用端口（与 LlmSummaryProvider 同源 client/config）。 */
    public com.matrix.agent.contract.LlmClient getTitleModelClient() {
        return titleModelClient;
    }

    public java.util.function.Supplier<com.matrix.agent.contract.ModelConfig>
    getTitleConfigSupplier() {
        return titleConfigSupplier;
    }

    /** 模型配置存储（ConversationGraph 的 ModelExecutionSnapshot 供应方用）。 */
    public com.matrix.agent.model.SecureModelConfigStore getModelConfigStore() {
        return modelConfigStore;
    }

    /** 全局线程预算登记处；download/voice 等域统一取池，不自建。 */
    public MatrixExecutorRegistry getExecutorRegistry() { return executorRegistry; }
    /** Process-owned network client family; only Host graphs may consume this dependency. */
    public MatrixHttpClient getHttpClient() { return httpClient; }

    public void shutdown() {
        Log.i(TAG, "[App] shutdown begin");
        mediaAvailability.close();
        try {
            agentRuntimeRepository.shutdown();
        } catch (Throwable t) {
            Log.w(TAG, "[App] shutdown: repository shutdown threw cause=" + t.getClass().getSimpleName());
        }
        try {
            executorRegistry.shutdown();
        } catch (Throwable t) {
            Log.w(TAG, "[App] shutdown: executorRegistry threw cause=" + t.getClass().getSimpleName());
        }
        try {
            auditEventRecorderField.shutdown();
        } catch (Throwable t) {
            Log.w(TAG, "[App] shutdown: auditEventRecorder threw cause=" + t.getClass().getSimpleName());
        }
        try {
            gatewayLifecycleManager.shutdown();
        } catch (Throwable t) {
            Log.w(TAG, "[App] shutdown: gatewayLifecycleManager threw cause=" + t.getClass().getSimpleName());
        }
        Log.i(TAG, "[App] shutdown done");
    }

    /**
     * SteerMailbox stale Steer 处理器——把 drop 事件转给 AuditEventRecorder。
     *
     * <p>仅记 type + payloadChars,**不记 payload 内容**(REPROMPT 可能含 PII)。
     * sessionId 不在 audit_event 字段中(只有 requestId/userId/zone/actor),用 requestId 占位
     * "stale-drop"(没有 AgentRequest 上下文,Repository.clearUserData 触发,不在请求路径)。
     *
     * <p>sessionId → (userId, zone, actor) 显式映射——
     * 旧实现 {@code sessionId.toUpperCase()} 得到 "DEMO-DRIVER",与 Repository.clearAuditSafe
     * 用的 "DRIVER"/"PASSENGER" 字面不一致,clearByUserZone 清不掉这些 stale-steer 行。
     * 当前 sessionId 使用 {@link ActorUsers} 的受控投影；接入真实多 session 时应把
     * 此映射替换为运行时的受信任 occupant resolver，而不是由客户端输入决定。
     */
    public static final class SteerMailboxStaleHandler implements StaleSteerHandler {
        private final AuditEventRecorder recorder;

        public SteerMailboxStaleHandler(AuditEventRecorder recorder) {
            this.recorder = recorder == null ? AuditEventRecorder.NOOP : recorder;
        }

        @Override
        public void onStaleSteerDropped(String sessionId, Steer steer, long stampedEpoch,
                long currentEpoch) {
            String userId;
            String zone;
            String actor;
            if (ActorUsers.USER_DRIVER.equals(sessionId)) {
                userId = ActorUsers.USER_DRIVER;
                zone = "DRIVER";
                actor = "DRIVER";
            } else if (ActorUsers.USER_PASSENGER.equals(sessionId)) {
                userId = ActorUsers.USER_PASSENGER;
                zone = "PASSENGER";
                actor = "PASSENGER";
            } else {
                userId = "";
                zone = "";
                actor = "";
            }
            recorder.recordSteerDroppedStale(
                    "stale-drop:" + sessionId,
                    userId,
                    zone,
                    actor,
                    steer.getType().name(),
                    steer.getPayload() == null ? 0 : steer.getPayload().length(),
                    currentEpoch);
        }
    }

}
