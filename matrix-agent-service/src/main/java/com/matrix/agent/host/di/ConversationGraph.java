package com.matrix.agent.host.di;

import android.util.Log;

import com.matrix.agent.conversation.ConversationCoordinator;
import com.matrix.agent.conversation.ConversationVoiceBindingStore;
import com.matrix.agent.conversation.ConversationIds;
import com.matrix.agent.conversation.ConversationRecoveryCoordinator;
import com.matrix.agent.conversation.ConversationServiceGate;
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
    private final ConversationVoiceBindingStore bindingStore;
    private final VoiceConversationBridge voiceBridge;
    private final ConversationServiceStub service;
    private final ConversationServiceGate gate;
    private final AtomicBoolean recoveryAttempted = new AtomicBoolean(false);

    ConversationGraph(@Nullable MatrixDatabase database,
            AgentRuntimeRepository runtime,
            ConversationTaskSubmitter submitter,
            ExecutorService conversationLane,
            ExecutorService databaseExecutor,
            PersistenceGate persistence,
            ModelServiceStub.CallerResolver callers) {
        if (database == null) {
            // 降级模式：不装配任何组件，gate 永不 open，featureFlags 不通告该位。
            this.coordinator = null;
            this.service = null;
            this.bindingStore = null;
            this.voiceBridge = null;
            this.gate = new ConversationServiceGate();
            return;
        }
        this.gate = new ConversationServiceGate();
        ConversationStore store = new RoomConversationStore(database, database::runInTransaction);
        KeyedSerialDispatcher dispatcher = new KeyedSerialDispatcher("matrix-conversation",
                conversationLane, DISPATCHER_MAX_PENDING);
        this.coordinator = new ConversationCoordinator(store, submitter, runtime::executePrepared,
                dispatcher,
                conversationId -> ConversationIds.agentSessionId(conversationId,
                        "DRIVER", "DRIVER"),
                runtime::offerSteer);
        this.bindingStore = new ConversationVoiceBindingStore();
        runtime.addConversationClearHook(bindingStore::clearAll);
        this.service = new ConversationServiceStub(coordinator, gate, persistence, callers, bindingStore);
        // 阶段 C：创建对话桥；配置器由 MatrixServiceGraph 注册到 VoiceRuntime，保证当前
        // 与未来（引擎切换后重建）的 Controller 都会接到同一套治理。
        this.voiceBridge = createVoiceBridge();
        recoverOffMainThread(databaseExecutor, store);
    }

    boolean isAvailable() {
        return coordinator != null;
    }

    ConversationVoiceBindingStore bindingStore() { return bindingStore; }

    VoiceConversationBridge voiceBridge() { return voiceBridge; }

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
        if (service != null) {
            service.shutdown();
        }
    }

    private void recoverOffMainThread(ExecutorService databaseExecutor,
            ConversationStore store) {
        try {
            databaseExecutor.execute(() -> {
                if (!recoveryAttempted.compareAndSet(false, true)) {
                    return;
                }
                try {
                    int recovered = new ConversationRecoveryCoordinator(store).recover();
                    gate.open();
                    Log.i(TAG, "[ConversationGraph] 恢复对账完成 interrupted=" + recovered);
                } catch (RuntimeException failure) {
                    // 对账失败保持关门——宁可拒绝服务，不冒“半套对话状态 +
                    // 幂等账本缺口”的风险；重启 Host 后重试。
                    Log.e(TAG, "[ConversationGraph] 恢复对账失败，对话域保持关闭", failure);
                }
            });
        } catch (RejectedExecutionException unavailable) {
            Log.e(TAG, "[ConversationGraph] 恢复任务被拒绝，对话域保持关闭", unavailable);
        }
    }
}
