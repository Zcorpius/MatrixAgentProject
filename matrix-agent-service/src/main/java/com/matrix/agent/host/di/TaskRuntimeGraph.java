package com.matrix.agent.host.di;

import com.matrix.agent.data.audit.AuditEventRecorder;
import com.matrix.agent.data.audit.AuditRepository;
import com.matrix.agent.data.memory.MemoryRecaller;
import com.matrix.agent.data.memory.MemoryStore;
import com.matrix.agent.data.memory.MemoryWriter;
import com.matrix.agent.task.persistence.AuditRepositoryAuditSink;
import com.matrix.agent.task.persistence.EpisodicMemorySink;
import com.matrix.agent.session.SessionLockManager;
import com.matrix.agent.session.SessionManager;
import com.matrix.agent.contract.GatewayLifecycleManager;
import com.matrix.agent.model.ModelApiClient;
import com.matrix.agent.contract.ModelConfig;
import com.matrix.agent.model.OnDeviceModelGateway;
import com.matrix.agent.model.SecureModelConfigStore;
import com.matrix.agent.task.AgentBudget;
import com.matrix.agent.task.AgentEngine;
import com.matrix.agent.task.AgentEngineConfiguration;
import com.matrix.agent.task.AgentRuntimeRepository;
import com.matrix.agent.platform.AuditDigest;
import com.matrix.agent.task.compress.ConversationCompressor;
import com.matrix.agent.task.DefaultContextUpdater;
import com.matrix.agent.demo.DemoModelGateway;
import com.matrix.agent.task.compress.LlmSummaryProvider;
import com.matrix.agent.task.ModelCallExecutor;
import com.matrix.agent.task.steer.SteerMailbox;
import com.matrix.agent.contract.SummaryProvider;
import com.matrix.agent.task.scheduler.TaskScheduler;
import com.matrix.agent.task.capability.CapabilityRegistry;
import com.matrix.agent.task.capability.CapabilityProvider;
import com.matrix.agent.intent.IntentClassifier;
import com.matrix.agent.intent.KeywordMemoryIntentDetector;
import com.matrix.agent.vehicle.VehicleStateSource;
import com.matrix.agent.task.policy.PolicyEngine;
import com.matrix.agent.task.prompt.DefaultPromptBuilder;
import com.matrix.agent.task.prompt.PromptContextAssembler;
import com.matrix.agent.task.token.Tokenizer;
import com.matrix.agent.task.tool.ToolExecutor;

/**
 * Task 域的组合根。
 *
 * <p>{@link AppContainer} 负责跨域基础设施（持久化、下载、线程池）；本类只负责把已经构造好的
 * task/model/memory 依赖装成可运行的 Repository。它也保证每次模型切换创建的 Engine 都是完整配置，
 * 不存在 setter 阶段的半初始化 Engine。</p>
 */
final class TaskRuntimeGraph {
    private final AgentRuntimeRepository repository;
    private final GatewayLifecycleManager lifecycleManager;

    TaskRuntimeGraph(Dependencies values) {
        values.requireComplete();
        PromptContextAssembler promptAssembler = new PromptContextAssembler(
                values.memoryRecaller, new DefaultPromptBuilder());
        AgentRuntimeRepository.AgentEngineFactory engineFactory = gateway -> {
            SummaryProvider summaryProvider = gateway instanceof OnDeviceModelGateway
                    ? null : new LlmSummaryProvider(values.modelClient, values.configStore::load);
            AgentEngineConfiguration configuration = new AgentEngineConfiguration.Builder()
                    .auditSink(new AuditRepositoryAuditSink(values.auditRepository))
                    .auditDigest(values.auditDigest)
                    .tokenizer(values.tokenizer)
                    .auditEventRecorder(values.auditEventRecorder)
                    .promptContextAssembler(promptAssembler)
                    .conversationCompressor(new ConversationCompressor(summaryProvider))
                    .taskMemoryWriter(new EpisodicMemorySink(values.memoryWriter))
                    .build();
            return new AgentEngine(gateway, values.modelCallExecutor, values.policyEngine,
                    values.registry, values.provider, values.sessionManager,
                    new DefaultContextUpdater(), values.sessionLockManager, values.toolExecutor,
                    values.budget, values.steerMailbox, configuration);
        };
        repository = new AgentRuntimeRepository(engineFactory, values.sessionManager,
                values.memoryStore, new DemoModelGateway(), "离线 DemoModelGateway", values.budget,
                values.scheduler, values.vehicleStateSource, values.registry, values.intentClassifier,
                values.auditRepository);
        repository.setSteerMailbox(values.steerMailbox);
        lifecycleManager = new GatewayLifecycleManager(values.lifecycleExecutor);
        repository.setGatewayLifecycleManager(lifecycleManager);
        repository.setAuditEventRecorder(values.auditEventRecorder);
        repository.setMemoryIntentDetector(KeywordMemoryIntentDetector.INSTANCE);
    }

    AgentRuntimeRepository repository() { return repository; }
    GatewayLifecycleManager lifecycleManager() { return lifecycleManager; }

    static final class Dependencies {
        ModelApiClient modelClient;
        SecureModelConfigStore configStore;
        ModelCallExecutor modelCallExecutor;
        PolicyEngine policyEngine;
        CapabilityRegistry registry;
        CapabilityProvider provider;
        SessionManager sessionManager;
        SessionLockManager sessionLockManager;
        ToolExecutor toolExecutor;
        AgentBudget budget;
        SteerMailbox steerMailbox;
        AuditRepository auditRepository;
        AuditDigest auditDigest;
        Tokenizer tokenizer;
        AuditEventRecorder auditEventRecorder;
        MemoryRecaller memoryRecaller;
        MemoryWriter memoryWriter;
        TaskScheduler scheduler;
        VehicleStateSource vehicleStateSource;
        MemoryStore memoryStore;
        IntentClassifier intentClassifier;
        java.util.concurrent.ExecutorService lifecycleExecutor;

        private void requireComplete() {
            if (modelClient == null || configStore == null || modelCallExecutor == null
                    || policyEngine == null || registry == null || provider == null
                    || sessionManager == null || sessionLockManager == null || toolExecutor == null
                    || budget == null || steerMailbox == null || auditRepository == null
                    || auditDigest == null || tokenizer == null || auditEventRecorder == null
                    || memoryRecaller == null || memoryWriter == null || scheduler == null
                    || vehicleStateSource == null || memoryStore == null || intentClassifier == null
                    || lifecycleExecutor == null) {
                throw new IllegalArgumentException("TaskRuntimeGraph dependencies must be complete");
            }
        }
    }
}
