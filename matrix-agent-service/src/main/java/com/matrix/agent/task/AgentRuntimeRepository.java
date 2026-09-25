package com.matrix.agent.task;
import com.matrix.agent.task.steer.*;
import com.matrix.agent.task.scheduler.*;

import com.matrix.agent.contract.ModelConfig;

import com.matrix.agent.vehicle.VehicleState;

import com.matrix.agent.intent.LlmIntentClassifier;

import com.matrix.agent.intent.FallbackIntentClassifier;

import android.util.Log;

import com.matrix.agent.contract.ModelGateway;
import com.matrix.agent.contract.GatewayLifecycleManager;
import com.matrix.agent.task.ModelRuntimeCoordinator;
import com.matrix.agent.task.AgentBudget;
import com.matrix.agent.task.AgentEngine;
import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.task.steer.SteerMailbox;
import com.matrix.agent.task.scheduler.TaskScheduler;
import com.matrix.agent.task.capability.CapabilityRegistry;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.identity.ActorUsers;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.identity.CancellationToken;
import com.matrix.agent.intent.IntentClassifier;
import com.matrix.agent.intent.KeywordIntentClassifier;
import com.matrix.agent.intent.KeywordMemoryIntentDetector;
import com.matrix.agent.intent.MemoryIntentDetector;
import com.matrix.agent.vehicle.VehicleStateSource;
import com.matrix.agent.identity.VehicleZone;
import com.matrix.agent.data.memory.MemoryStore;
import com.matrix.agent.session.SessionManager;
import com.matrix.agent.data.audit.AuditEventRecorder;
import com.matrix.agent.data.audit.AuditRepository;
import com.matrix.agent.data.audit.NoopAuditRepository;
import com.matrix.agent.task.persistence.AuditRepositoryAuditSink;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AgentRuntimeRepository {
    private static final String TAG = "MatrixAgent";
    /**
     * 主驾和副驾共享同一调度仲裁队列(arbitrationKey),
     * 让 TaskScheduler 的主驾优先抢占协议在 APK 路径真正可触达。
     *
     * <p>直接把 sessionId 共享导致 SessionManager.getOrCreate
     * 与 SteerMailbox 也跟着共享——副驾的 REPROMPT / FORCE_TOOL / DEFER 可能进入主驾 mailbox,
     * 后续上下文持久化时也会发生主副驾上下文串扰。改为拆成两个 key:
     * <ul>
     *   <li>{@code ARBITRATION_KEY = "demo-vehicle"} —— TaskScheduler 用的同车仲裁键</li>
     *   <li>{@code sessionId = "demo-driver" / "demo-passenger"} —— SessionManager / SteerMailbox 用的乘员隔离键</li>
     * </ul>
     */
    private static final String ARBITRATION_KEY = "demo-vehicle";
    private final SessionManager sessionManager;
    /**
     * AgentRequest.timeoutMillis 必须用 budget.totalDeadlineMillis 作为单一权威来源,
     * 不再硬编码 60_000L。AppContainer 把同一个 AgentBudget 注入 Repository 和 AgentEngine。
     */
    private final TaskRequestFactory requestFactory;
    private final VehicleStateSource vehicleStateSource;
    /**
     * 主驾优先调度器——在此真正接入 APK 链路。
     * Repository.execute 改走 {@link TaskScheduler#submit},把抢占/排队语义生效。
     */
    private final TaskScheduler scheduler;
    /**
     * 车辆运动状态源——Repository.execute build 时调
     * {@link VehicleStateSource#snapshot()} 注入到 AgentRequest,
     * 让 PolicyEngine 的 requiredVehicleStates 前置约束真正生效。
     */
    /**
     * 模型调用前的查询/写意图分类器——决定 readOnlyHint,
     * 让车控写操作不被主驾优先调度半路强制中断。后续可替换为 LLM-based classifier。
     *
     * <p>改为 volatile —— AppContainer.setModelGateway 切换 LLM 网关时,
     * 通过 {@link #setIntentClassifier(IntentClassifier)} 替换为 FallbackIntentClassifier
     * (新 LlmIntentClassifier + Keyword),让运行时分类器与 Provider 配置同步演进。
     */
    /**
     * 显式语义记忆意图检测器——决定 {@code AgentRequest.memorySaveAllowed},
     * 让 {@code memory.semantic.save} capability handler 在 false 时直接 POLICY_REJECTED。
     *
     * <p>默认 {@link MemoryIntentDetector#NOOP}(始终返回 false)——保守起点,AppContainer
     * 装配 {@link KeywordMemoryIntentDetector#INSTANCE} 后才放行命中关键词的请求。与
     * {@link #intentClassifier} 同 volatile + setter 模式,后续版本可替换为 LLM-based detector。
     */
    private final ModelRuntimeCoordinator modelRuntime;
    /**
     * Repository 层兜底 Audit——保证所有终态(含 Repository
     * catch 分支构造的 TIMED_OUT / CANCELLED + Scheduler 内部生成的 TIMED_OUT / CANCELLED)
     * 都进 Audit,与 AgentEngine 5 个出口点审计短期并存(requestId 作幂等键,REPLACE 语义)。
     */
    private final UserDataResetCoordinator userDataResetCoordinator;
    private final TaskDispatchCoordinator taskDispatchCoordinator;
    /**
     * 当前在途任务 token——{@link #clearUserData()} 时统一 cancel(),
     * 让 Engine 进入协作取消 + grace window 收敛(收敛窗口已就位),
     * 避免写操作清完后才落盘,把刚清的数据写回。
     */
    private final InFlightTaskRegistry inFlightTasks = new InFlightTaskRegistry();
    /**
     * Repository 必须能 clearAll SteerMailbox——AppContainer 显式注入。
     * 为兼容现有测试(不依赖 mailbox 清理),setter 为可选;clearUserData 调用时 null 仅记 warn。
     */
    /**
     * 增量 audit_event recorder——可选注入(默认 NOOP)。
     *
     * <p>clearUserDataDetailed 在 epoch 自增后调 {@link AuditEventRecorder#advanceEpoch(long)}
     * 让在队列里、还未落库的旧 epoch 事件被 gate drop;在清理 audit 表前调
     * {@link AuditEventRecorder#dropByUserZone(String, String, long)} 把 pending 队列里
     * 指定 user+zone 的事件 drain 掉。两步配合 epoch gate,杜绝"clearUserData 后异步
     * AuditEvent 又把旧事件重新 insert 回去"。
     */
    /**
     * MemoryStore 是 epoch 单一权威。
     *
     * <p>删除本类的 epochCounter——评审指出双来源在进程重启后失步 (Repository 重建回到 0,
     * 历史偏好存储从持久化载入旧 epoch，导致 clearData 重启再 clearData 后
     * 新写入被错误拒绝。改为 execute 入口直接读 {@link MemoryStore#currentEpoch()},
     * clearUserData 只调 {@link MemoryStore#bumpEpoch()},二者始终同步。
     */

    public AgentRuntimeRepository(AgentEngineFactory engineFactory,
            SessionManager sessionManager, MemoryStore memoryStore,
            ModelGateway initialGateway, String initialDisplayName, TaskScheduler scheduler,
            VehicleStateSource vehicleStateSource, CapabilityRegistry registry) {
        this(engineFactory, sessionManager, memoryStore, initialGateway,
                initialDisplayName, new AgentBudget(), scheduler, vehicleStateSource, registry,
                KeywordIntentClassifier.INSTANCE, NoopAuditRepository.INSTANCE);
    }

    public AgentRuntimeRepository(AgentEngineFactory engineFactory,
            SessionManager sessionManager, MemoryStore memoryStore,
            ModelGateway initialGateway, String initialDisplayName, AgentBudget budget,
            TaskScheduler scheduler, VehicleStateSource vehicleStateSource, CapabilityRegistry registry) {
        this(engineFactory, sessionManager, memoryStore, initialGateway,
                initialDisplayName, budget, scheduler, vehicleStateSource, registry,
                KeywordIntentClassifier.INSTANCE, NoopAuditRepository.INSTANCE);
    }

    /** 注入自定义 IntentClassifier(测试 / 未来 LLM-based 替换用)。 */
    public AgentRuntimeRepository(AgentEngineFactory engineFactory,
            SessionManager sessionManager, MemoryStore memoryStore,
            ModelGateway initialGateway, String initialDisplayName, AgentBudget budget,
            TaskScheduler scheduler, VehicleStateSource vehicleStateSource, CapabilityRegistry registry,
            IntentClassifier intentClassifier) {
        this(engineFactory, sessionManager, memoryStore, initialGateway,
                initialDisplayName, budget, scheduler, vehicleStateSource, registry,
                intentClassifier, NoopAuditRepository.INSTANCE);
    }

    /**
     * Repository 层兜底 Audit 装配入口。
     *
     * <p>AppContainer 显式传入 RoomAuditRepository(与 AgentEngine 共享同一实例);
     * Repository.execute 在所有 return outcome 路径统一 persist,与 Engine 5 出口点
     * 短期并存(requestId 作幂等键,TrajectoryDao `@Insert(REPLACE)` 让 Repository 后写覆盖)。
     *
     * <p>旧 3 个构造器链默认 NoopAuditRepository.INSTANCE,现有测试 0 回归。
     */
    public AgentRuntimeRepository(AgentEngineFactory engineFactory,
            SessionManager sessionManager, MemoryStore memoryStore,
            ModelGateway initialGateway, String initialDisplayName, AgentBudget budget,
            TaskScheduler scheduler, VehicleStateSource vehicleStateSource, CapabilityRegistry registry,
            IntentClassifier intentClassifier, AuditRepository auditRepository) {
        if (scheduler == null) throw new IllegalArgumentException("scheduler 不能为空");
        if (vehicleStateSource == null) throw new IllegalArgumentException("vehicleStateSource 不能为空");
        if (registry == null) throw new IllegalArgumentException("registry 不能为空");
        if (intentClassifier == null) throw new IllegalArgumentException("intentClassifier 不能为空");
        if (auditRepository == null) throw new IllegalArgumentException("auditRepository 不能为空");
        this.sessionManager = sessionManager;
        AgentBudget safeBudget = budget == null ? new AgentBudget() : budget;
        this.scheduler = scheduler;
        this.vehicleStateSource = vehicleStateSource;
        this.requestFactory = new TaskRequestFactory(memoryStore, safeBudget, vehicleStateSource,
                intentClassifier);
        this.userDataResetCoordinator = new UserDataResetCoordinator(memoryStore, sessionManager,
                auditRepository, inFlightTasks);
        this.taskDispatchCoordinator = new TaskDispatchCoordinator(scheduler,
                new AuditRepositoryAuditSink(auditRepository), inFlightTasks);
        this.modelRuntime = new ModelRuntimeCoordinator(engineFactory, initialGateway, initialDisplayName);
    }

    public AgentOutcome execute(String command, Actor actor, CancellationToken token) {
        // 文本/触摸入口:沿用 Builder 默认 inputSource=TOUCH / languageTag=zh-CN /
        // asrConfidence=1.0f / confidenceAvailable=true,与此前逐字节等价(既有测试零回归)。
        return dispatch(newRequestBuilder(command, actor, token).build(), token);
    }

    /**
     * Host-only entry with a unique, server-owned session id.  The public Binder request never
     * supplies actor, arbitration or vehicle state; those are still derived here.  Separating
     * sessions prevents a steer or conversational context from one durable task leaking into
     * another caller's task.
     */
    public AgentOutcome executeForSession(String command, Actor actor, String sessionId,
            CancellationToken token) {
        if (sessionId == null || !sessionId.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("invalid server sessionId");
        }
        return dispatch(newRequestBuilder(command, actor, token, sessionId, ARBITRATION_KEY).build(),
                token);
    }

    /** Enqueues the only two externally permitted runtime controls for the given host task. */
    public boolean offerSteer(String sessionId, Steer steer) {
        return userDataResetCoordinator.offerSteer(sessionId, steer);
    }

    /**
     * 对话任务的执行入口（keyed lane 出队线程调用；阻塞至终态）。
     *
     * <p>与 {@link #execute(AgentInvocation)} 的差异：分类快照、稳定 requestId、
     * agentSession/arbitrationKey 与对话种子全部来自 {@link PreparedTask}（提交期固化），
     * 本方法只做 request 构造与既有调度派发——deadline 从此处构造 request 起算，
     * 排队等待不消耗任务预算（设计文档 §4.3）。</p>
     */
    public AgentOutcome executePrepared(
            com.matrix.agent.task.conversation.ConversationTaskSubmitter.PreparedTask task,
            CancellationToken token) {
        if (task == null) throw new IllegalArgumentException("task 不能为空");
        if (token == null) throw new IllegalArgumentException("token 不能为空");
        AgentRequest request = requestFactory.newPreparedRequestBuilder(task, token).build();
        return dispatch(request, token);
    }

    /**
     * Executes a host-adapted invocation while task keeps ownership of all derived request state.
     * The caller may be voice, touch, or a future Binder adapter; this class never imports those
     * presentation/runtime packages.
     */
    public AgentOutcome execute(AgentInvocation invocation) {
        if (invocation == null) throw new IllegalArgumentException("invocation 不能为空");
        CancellationToken token = invocation.cancellationToken();
        AgentRequest request = newRequestBuilder(invocation.text(), invocation.actor(), token)
                .inputSource(invocation.inputSource())
                .languageTag(invocation.languageTag())
                .asrConfidence(invocation.confidence())
                .confidenceAvailable(invocation.confidenceAvailable())
                .audioZoneId(invocation.audioZoneId())
                .build();
        return dispatch(request, token);
    }

    /**
     * 构造请求 Builder 的公共派生段:sessionId(乘员隔离)/arbitrationKey(同车仲裁)/
     * intentReadOnly(IntentClassifier)/memorySaveAllowed(MemoryIntentDetector)/epoch(MemoryStore
     * 单一权威)/vehicleState 实时快照。不 build——调用方(文本/语音入口)各自补
     * inputSource/languageTag/asrConfidence/audioZoneId/confidenceAvailable(或走 Builder 默认)后再 build。
     */
    private AgentRequest.Builder newRequestBuilder(String command, Actor actor, CancellationToken token) {
        // Demo session identity deliberately follows the canonical actor-to-user projection.
        // OEM integration replaces this projection in ActorUsers rather than teaching every
        // task entry point a second identity mapping.
        String sessionId = ActorUsers.userIdOf(actor);
        return newRequestBuilder(command, actor, token, sessionId, ARBITRATION_KEY);
    }

    private AgentRequest.Builder newRequestBuilder(String command, Actor actor, CancellationToken token,
            String sessionId, String arbitrationKey) {
        return requestFactory.newRequestBuilder(command, actor, token, sessionId, arbitrationKey);
    }

    /**
     * 执行段:TaskScheduler 提交 + deadline/超时/中断兜底 + Repository 层 audit。
     * catch 分支按 {@link AgentRequest#isReadOnlyHint()} 决定写操作的 EXECUTION_UNKNOWN 保守语义。
     */
    private AgentOutcome dispatch(AgentRequest request, CancellationToken token) {
        return taskDispatchCoordinator.dispatch(request, token, modelRuntime.currentEngine());
    }

    public synchronized void setModelGateway(ModelGateway gateway, String displayName) {
        modelRuntime.switchGateway(gateway, displayName);
    }

    public String getActiveModelGateway() { return modelRuntime.activeDisplayName(); }

    /**
     * 最后一次端侧推理的性能统计（token/s + 当前 native heap 采样 + prefill/decode 耗时），供 UI 展示。
     *
     * <p>统计由运行时模型协调器维护；云端模型或不提供采样的模型返回 {@code null}。
     */
    public String getLastOnDeviceStats() {
        return modelRuntime.lastOnDeviceStats();
    }
    /**
     * Returns telemetry used by PolicyEngine, not the provider's commanded/observed demo map.
     * Keeping these two concepts separate prevents UI diagnostics from claiming a command is
     * physical vehicle truth.
     */
    public Map<String, Object> getVehicleState() {
        com.matrix.agent.vehicle.VehicleState state = vehicleStateSource.snapshot();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("gear", state.getGear().name());
        result.put("speedKmh", state.getSpeedKmh());
        result.put("engineRunning", state.isEngineRunning());
        result.put("charging", state.isCharging());
        result.put("batteryPercent", state.getBatteryPercent());
        return java.util.Collections.unmodifiableMap(result);
    }

    public Map<String, List<String>> getSessionTurns() { return sessionManager.snapshotTurns(); }

    /**
     * Repository 必须能 clearAll SteerMailbox——AppContainer 显式注入。
     *
     * <p>为兼容现有测试(289 个测试不依赖 mailbox 清理),setter 为可选;
     * {@link #clearUserData()} 调用时 {@code mailbox == null} 仅记 warn 不 NPE。
     */
    public void setSteerMailbox(SteerMailbox mailbox) {
        userDataResetCoordinator.setSteerMailbox(mailbox);
    }

    /**
     * 替换意图分类器——AppContainer 在 setModelGateway 切换 LLM 网关时,
     * 用新 ModelConfig 重新构造 FallbackIntentClassifier(LlmIntentClassifier, Keyword) 并注入。
     *
     * <p>volatile 替换,{@link #execute} 路径下次读取即生效。null 输入退化为
     * {@link KeywordIntentClassifier#INSTANCE}(防御)。
     */
    public void setIntentClassifier(IntentClassifier classifier) {
        requestFactory.setIntentClassifier(classifier);
    }

    /** 注入端侧 gateway 生命周期管理器（可选；null 时切模型不退役旧端侧 gateway）。 */
    public void setGatewayLifecycleManager(GatewayLifecycleManager manager) {
        modelRuntime.setLifecycleManager(manager);
    }

    /**
     * 注入显式语义记忆意图检测器——AppContainer 装配 KeywordMemoryIntentDetector。
     *
     * <p>volatile 替换,{@link #execute} 路径下次读取即生效。null 输入退化为
     * {@link MemoryIntentDetector#NOOP}(始终返回 false,保守拒绝所有 semantic.save)。
     *
     * <p>语义对偶:{@link #setIntentClassifier} 输出 readOnly 用于 TaskScheduler 抢占;
     * 本 setter 输出 explicitMemorySave 用于 capability handler gate。两接口关注点不同,不混。
     */
    public void setMemoryIntentDetector(MemoryIntentDetector detector) {
        requestFactory.setMemoryIntentDetector(detector);
    }

    /**
     * 注入增量 audit_event recorder(可选,默认 NOOP)。
     *
     * <p>AppContainer 装配 auditEventRecorder 后调用此 setter。clearUserDataDetailed 会:
     * <ol>
     *   <li>memoryStore.clearUserDataAndBump 拿到 newEpoch 后调 advanceEpoch(newEpoch),
     *       让 recorder 内 stale gate 拒绝旧 epoch 事件入队;</li>
     *   <li>清空 audit 表前调 dropByUserZone(userId, zone, newEpoch),
     *       drain pending 队列中匹配 user+zone 的事件,同时把它们加进 stale gate。</li>
     * </ol>
     */
    public void setAuditEventRecorder(AuditEventRecorder recorder) {
        userDataResetCoordinator.setAuditEventRecorder(recorder);
    }

    /**
     * 重写为"取消—等待—清空"序列,保证原子性。
     *
     * <p>评审场景:旧实现仅清 MemoryStore + SessionManager,与在途写操作非原子——
     * <ul>
     *   <li>{@code clearUserData()} 之前 dispatch 的 preference.save / climate.set
     *       可能在 clear 之后完成,把刚清的数据写回 MemoryStore;</li>
     *   <li>{@link SteerMailbox} 没清,旧 FORCE_TOOL / REPROMPT / DEFER 留在队列里,
     *       被下一次同 sessionId 任务消费到陈旧指令。</li>
     * </ul>
     *
     * <p>新实现:
     * <ol>
     *   <li><b>取消</b>:统一 cancel 所有在途 token,触发 Engine abort hooks;</li>
     *   <li><b>等待</b>:最多 1000ms(2× grace window) 等 activeTokens 收敛——
     *       让在途写操作要么在 clear 之前完成,要么被 token.cancel 后的
     *       EXECUTION_UNKNOWN 路径放弃 observation 写回;</li>
     *   <li><b>清空</b>:SteerMailbox + MemoryStore(driver + passenger,原子 clearUserDataAndBump)
     *       + SessionManager。</li>
     * </ol>
     *
     * <p>已落地 MemoryStore 单一权威 epoch(见 §16),也已落地"epoch 自增 + clear×2"
     * 原子操作(MemoryStore.clearUserDataAndBump,见 §17)——杜绝 check-then-act race。
     * Steer 与记忆异步写回均带 epoch gate；清空后的旧 generation 不能重新写回用户域。
     */
    public void clearUserData() {
        ClearUserDataOutcome outcome = clearUserDataDetailed();
        if (!outcome.isComplete()) {
            throw new IllegalStateException("user data clear incomplete: " + outcome.summary());
        }
    }

    /**
     * 结构化返回版 clearUserData——透传 driver/passenger 两 zone 的
     * {@link ClearOutcome},让 ViewModel 据此选择"已清空" vs "上下文已清,审计删除失败"文案。
     *
     * <p>主流程(MemoryStore / SteerMailbox / Session)失败仍抛 RuntimeException,
     * ViewModel 走"清空失败 [...]"路径——失败时不构造 ClearUserDataOutcome。
     *
     * <p>审计维度失败封装进 {@link ClearUserDataOutcome},Repository 不抛——主流程已成功
     * 清空偏好 + 会话,不让 audit 失败回滚主流程;但 UI 必须显式提示"审计删除失败,请重试",
     * 避免"audit 仍残留"伪装成"完整成功"。
     */
    public ClearUserDataOutcome clearUserDataDetailed() {
        return userDataResetCoordinator.clear();
    }

    /**
     * 对话表清理钩子（clearUserData 覆盖范围，设计文档 §5.2）；AppContainer 装配期注入。
     * null 时仅记 warn——与 steerMailbox 可选注入同一兼容契约。
     */
    public void setConversationClearHook(Runnable hook) {
        userDataResetCoordinator.setConversationClearHook(hook);
    }

    public void setLegacyMemoryClearHook(java.util.function.BooleanSupplier hook) {
        userDataResetCoordinator.setLegacyMemoryClearHook(hook);
    }

    public void setResetLifecycleHooks(Runnable begin, Runnable complete) {
        userDataResetCoordinator.setResetLifecycleHooks(begin, complete);
    }

    /** 追加进程内对话附属状态的清理（如一次性语音绑定），不覆盖持久化正文清理。 */
    public void addConversationClearHook(Runnable hook) {
        userDataResetCoordinator.addConversationClearHook(hook);
    }

    /** Host 进程结束时的 scheduler shutdown 钩子。 */
    public void shutdown() {
        Log.i(TAG, "[Repo] shutdown scheduler");
        scheduler.shutdown();
    }

    @FunctionalInterface
    public interface AgentEngineFactory {
        AgentEngine create(ModelGateway gateway);
    }
}
