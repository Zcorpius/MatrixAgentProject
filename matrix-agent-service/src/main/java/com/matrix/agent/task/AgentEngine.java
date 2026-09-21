package com.matrix.agent.task;
import com.matrix.agent.task.compress.*;
import com.matrix.agent.task.redact.*;
import com.matrix.agent.task.steer.*;

import com.matrix.agent.identity.VehicleZone;

import com.matrix.agent.identity.Actor;

import android.util.Log;

import com.matrix.agent.contract.AgentMessage;
import com.matrix.agent.contract.FinishReason;
import com.matrix.agent.contract.ModelGateway;
import com.matrix.agent.contract.ModelTurn;
import com.matrix.agent.contract.ModelTurnRequest;
import com.matrix.agent.contract.ToolCall;
import com.matrix.agent.contract.ToolDefinition;
import com.matrix.agent.task.capability.CapabilityDefinition;
import com.matrix.agent.task.capability.CapabilityProvider;
import com.matrix.agent.task.capability.CapabilityRegistry;
import com.matrix.agent.identity.ActorUsers;
import com.matrix.agent.identity.AgentRequest;
import com.matrix.agent.task.policy.PolicyDecision;
import com.matrix.agent.task.policy.PolicyEngine;
import com.matrix.agent.task.prompt.PromptContextAssembler;
import com.matrix.agent.session.SessionContext;
import com.matrix.agent.session.SessionLockManager;
import com.matrix.agent.session.SessionManager;
import com.matrix.agent.task.token.Tokenizer;
import com.matrix.agent.task.tool.ToolExecutor;
import com.matrix.agent.task.tool.ToolResult;
import com.matrix.agent.data.audit.AuditEventRecorder;
import com.matrix.agent.task.port.TaskAuditSink;
import com.matrix.agent.task.port.TaskMemoryWriter;
import com.matrix.agent.api.debug.DebugTracePayloads;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent Loop Runtime。
 *
 * 主循环:LLM → ToolCall → Policy → Execute → Observation → LLM。
 * 每轮先做 5 个终止检查(cancel / deadline / 字符预算 / 无 tool_call / tool call 累计上限),
 * 再走 ModelCallExecutor 包裹 ModelGateway 单轮决策,然后顺序处理模型给出的 ToolCall。
 *
 * Policy 二分:
 * - CAPABILITY 拒绝(R3/越权/未注册/区域越界)被累计进 blockedCapabilities,本轮和后续迭代都不再放行。
 * - PARAMETER 拒绝(数值/格式/可补全)回传 Observation,模型可在剩余预算内换参数重试。
 *
 * 终止原因见 {@link StopReason};最终 TaskState 由 {@link #computeFinalState} 给出。
 */
public final class AgentEngine {
    private static final String TAG = "MatrixAgent";

    private final ModelGateway modelGateway;
    private final ModelCallExecutor modelCallExecutor;
    private final PolicyEngine policyEngine;
    private final CapabilityRegistry registry;
    private final CapabilityProvider provider;
    private final SessionManager sessionManager;
    private final ContextUpdater contextUpdater;
    private final SessionLockManager sessionLockManager;
    private final ToolExecutor toolExecutor;
    private final AgentBudget budget;
    private final ModelSanitizer modelSanitizer;
    private final AuditRedactor auditRedactor;
    // 可选 SteerMailbox,null 时跳过 drain(向后兼容旧构造器)。
    private final SteerMailbox steerMailbox;
    /**
     * Audit 持久化——5 个出口点统一调用。
     * 默认 {@link TaskAuditSink#NOOP};AppContainer 在装配层适配 SQLCipher 实现。
     */
    private final TaskAuditSink auditSink;
    /** 系统 Prompt、记忆召回与降级策略的专属组件。 */
    private final PromptContextAssembler promptContextAssembler;
    /**
     * Tokenizer——主路径双轨统计用,不影响 budget 决策。
     *
     * <p>null 时退化为 char-based 估算(与旧版 / 289 测试一致);AppContainer 注入
     * JtokkitTokenizer 后,关键日志点(BEGIN / 迭代边界 / Tool 写入)输出双轨
     * {@code convChars=N convTokens=M},便于后续决定切主路径时机。
     *
     * <p>主路径 {@link #appendMessageWithBudget} 仍用 {@link #estimateConversationChars}
     * + {@link AgentBudget#getTotalInputChars()} / {@link AgentBudget#getMaxMessageChars()}。
     */
    private final Tokenizer tokenizer;
    /**
     * audit_event 增量事件落库——PRE_TOOL / POST_TOOL / POLICY / STEER。
     *
     * <p>默认 {@link AuditEventRecorder#NOOP}(289 测试 + 旧版兼容);AppContainer
     * 注入真实 recorder 后,Loop 4 个出口点写入 audit_event 表(异步 fire-and-forget,
     * Dao 异常仅 log 不阻塞主路径)。另加 STEER_DROPPED_STALE 第 5 个出口点。
     */
    private final AuditEventRecorder auditEventRecorder;
    /**
     * Memory 写入——Episodic 自动 + Semantic 显式。
     *
     * <p>默认 {@link TaskMemoryWriter#NOOP};AppContainer 在装配层适配
     * RoomMemoryWriter 后,主路径终态出口点(L678)+ terminalOutcome 出口点(L783)
     * 写入 session_history 表,供 EpisodicMemorySourceImpl 召回。
     */
    private final TaskMemoryWriter memoryWriter;
    /**
     * ConversationCompressor——appendMessageWithBudget 失败前压缩旧 turns。
     *
     * <p>null 时退化为旧行为(直接 BUDGET_EXHAUSTED);AppContainer 注入后走
     * LLM 摘要 + heuristic 降级双路径。
     */
    private final ConversationCompressor conversationCompressor;

    public AgentEngine(ModelGateway modelGateway, ModelCallExecutor modelCallExecutor,
            PolicyEngine policyEngine, CapabilityRegistry registry, CapabilityProvider provider,
            SessionManager sessionManager, ContextUpdater contextUpdater,
            SessionLockManager sessionLockManager, ToolExecutor toolExecutor) {
        this(modelGateway, modelCallExecutor, policyEngine, registry, provider, sessionManager,
                contextUpdater, sessionLockManager, toolExecutor, new AgentBudget());
    }

    public AgentEngine(ModelGateway modelGateway, ModelCallExecutor modelCallExecutor,
            PolicyEngine policyEngine, CapabilityRegistry registry, CapabilityProvider provider,
            SessionManager sessionManager, ContextUpdater contextUpdater,
            SessionLockManager sessionLockManager, ToolExecutor toolExecutor, AgentBudget budget) {
        this(modelGateway, modelCallExecutor, policyEngine, registry, provider, sessionManager,
                contextUpdater, sessionLockManager, toolExecutor, budget, null);
    }

    /** 注入 SteerMailbox 的全参构造器。null 时跳过 drain,行为与旧版一致。 */
    public AgentEngine(ModelGateway modelGateway, ModelCallExecutor modelCallExecutor,
            PolicyEngine policyEngine, CapabilityRegistry registry, CapabilityProvider provider,
            SessionManager sessionManager, ContextUpdater contextUpdater,
            SessionLockManager sessionLockManager, ToolExecutor toolExecutor,
            AgentBudget budget, SteerMailbox steerMailbox) {
        this(modelGateway, modelCallExecutor, policyEngine, registry, provider, sessionManager,
                contextUpdater, sessionLockManager, toolExecutor, budget, steerMailbox,
                AgentEngineConfiguration.defaults());
    }

    /**
     * 生产装配入口。所有可选运行能力在构造时固定，Engine 不会以半初始化状态运行。
     */
    public AgentEngine(ModelGateway modelGateway, ModelCallExecutor modelCallExecutor,
            PolicyEngine policyEngine, CapabilityRegistry registry, CapabilityProvider provider,
            SessionManager sessionManager, ContextUpdater contextUpdater,
            SessionLockManager sessionLockManager, ToolExecutor toolExecutor,
            AgentBudget budget, SteerMailbox steerMailbox,
            AgentEngineConfiguration configuration) {
        if (modelGateway == null) throw new IllegalArgumentException("modelGateway 不能为空");
        if (modelCallExecutor == null) throw new IllegalArgumentException("modelCallExecutor 不能为空");
        if (policyEngine == null) throw new IllegalArgumentException("policyEngine 不能为空");
        if (registry == null) throw new IllegalArgumentException("registry 不能为空");
        if (provider == null) throw new IllegalArgumentException("provider 不能为空");
        if (sessionManager == null) throw new IllegalArgumentException("sessionManager 不能为空");
        if (contextUpdater == null) throw new IllegalArgumentException("contextUpdater 不能为空");
        if (sessionLockManager == null) throw new IllegalArgumentException("sessionLockManager 不能为空");
        if (toolExecutor == null) throw new IllegalArgumentException("toolExecutor 不能为空");
        if (budget == null) throw new IllegalArgumentException("budget 不能为空");
        this.modelGateway = modelGateway;
        this.modelCallExecutor = modelCallExecutor;
        this.policyEngine = policyEngine;
        this.registry = registry;
        this.provider = provider;
        this.sessionManager = sessionManager;
        this.contextUpdater = contextUpdater;
        this.sessionLockManager = sessionLockManager;
        this.toolExecutor = toolExecutor;
        this.budget = budget;
        this.steerMailbox = steerMailbox;
        AgentEngineConfiguration safeConfiguration = configuration == null
                ? AgentEngineConfiguration.defaults() : configuration;
        this.auditSink = safeConfiguration.auditSink();
        this.promptContextAssembler = safeConfiguration.promptContextAssembler();
        this.memoryWriter = safeConfiguration.memoryWriter();
        this.auditEventRecorder = safeConfiguration.auditEventRecorder();
        this.tokenizer = safeConfiguration.tokenizer();
        this.conversationCompressor = safeConfiguration.conversationCompressor();
        this.modelSanitizer = new ModelSanitizer(budget.getMaxMessageChars());
        // 注入 CapabilityRegistry,AuditRedactor 按 schema 脱敏 memory.preference.value、
        // navigation.destination、contact.phone 等业务敏感字段,不再依赖自由文本凭据正则。
        this.auditRedactor = new AuditRedactor(budget.getMaxMessageChars(), registry);
        this.auditRedactor.setDigest(safeConfiguration.auditDigest());
        Log.i(TAG, "[Engine] init gateway=" + modelGateway.getClass().getSimpleName()
                + " budget=" + budget
                + " steerMailbox=" + (steerMailbox == null ? "off" : "on")
                + " audit=" + this.auditSink.getClass().getSimpleName()
                + " promptContext=" + promptContextAssembler.getClass().getSimpleName());
    }

    /** 对外暴露的追加转向指令入口,null mailbox 时直接返回 false。 */
    public boolean offerSteer(String sessionId, Steer steer) {
        if (steerMailbox == null) {
            Log.w(TAG, "[Engine] offerSteer ignored, no SteerMailbox configured");
            return false;
        }
        steerMailbox.offer(sessionId, steer);
        return true;
    }

    public AgentOutcome execute(AgentRequest request) {
        long started = System.nanoTime();
        // BEGIN 行只暴露元数据,用户输入原文用 SafeLog 占位符包裹。
        // 旧实现把 truncate(text, 80) 直接写 logcat——前 80 字符可能含目的地 / 联系人 / 偏好值。
        Log.i(TAG, "[Engine] BEGIN req=" + request.getRequestId()
                + " session=" + request.getSessionId()
                + " actor=" + request.getActor()
                + " zone=" + request.getOccupantZone()
                + " input=" + request.getInputSource()
                + " remainingMs=" + request.remainingMillis()
                + " text=" + SafeLog.USER_INPUT_PLACEHOLDER
                + " textChars=" + safeLength(request.getText()));
        try (SessionLockManager.Handle handle = sessionLockManager.tryAcquire(
                request.getSessionId(), request.remainingMillis())) {
            if (handle == null) {
                Log.w(TAG, "[Engine] session lock acquire timeout, terminal=TIMED_OUT");
                return terminalOutcome(request, TaskState.TIMED_OUT, StopReason.TIMEOUT,
                        "等待会话执行锁超时", started);
            }
            Log.d(TAG, "[Engine] session lock acquired for " + request.getSessionId());
            return executeLocked(request, started);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "[Engine] interrupted by cancellation, terminal=CANCELLED");
            return terminalOutcome(request, TaskState.CANCELLED, StopReason.CANCELLED,
                    "任务已取消", started);
        }
    }

    private AgentOutcome executeLocked(AgentRequest request, long started) {
        // 调试轨迹：轮次开始（ROUND_START）
        com.matrix.agent.debugtrace.DebugTraceHolder.emit(
                com.matrix.agent.debugtrace.DebugTraceEvent.PHASE_ROUND_START,
                request.getRequestId(),
                "textChars=" + safeLength(request.getText())
                        + " deadlineMs=" + request.getDeadlineAtMillis());
        Trajectory trajectory = new Trajectory();
        SessionContext sessionContext = sessionManager.getOrCreate(request.getSessionId());
        Log.d(TAG, "[Engine] session context ready turns=" + sessionContext.getRecentTurns().size());

        TaskState preTerminal = cancellationState(request);
        if (preTerminal != null) {
            StopReason reason = preTerminal == TaskState.CANCELLED
                    ? StopReason.CANCELLED : StopReason.TIMEOUT;
            Log.w(TAG, "[Engine] pre-loop terminal state=" + preTerminal + " reason=" + reason);
            trajectory.finish(reason, elapsedMillis(started), 0);
            sessionContext.addTurn("USER: " + SafeLog.USER_INPUT_PLACEHOLDER
                    + " | RESULT: " + preTerminal);
            AgentOutcome preLoopOutcome = new AgentOutcome(request.getRequestId(), preTerminal,
                    reason, trajectory, elapsedMillis(started));
            // 出口点 3——pre-loop terminal(cancel/deadline 已触发)
            auditSink.persist(preLoopOutcome, request);
            // pre-loop terminal 也要写 episodic(覆盖 CANCELLED/TIMED_OUT 终态)。
            // 传 request.getEpoch(),RoomMemoryWriter 在 Room 事务内做 epoch gate。
            memoryWriter.writeEpisodicOnTerminal(request, preLoopOutcome, request.getEpoch());
            return preLoopOutcome;
        }

        String systemPrompt = buildSystemPrompt(request);
        // per-request zone 投影——主驾/副驾看到不同 tool 列表,
        // 此前加的 toToolDefinitions(VehicleZone) 才真正接入。
        List<ToolDefinition> tools = registry.toToolDefinitions(request.getOccupantZone());
        List<AgentMessage> conversation = new ArrayList<>();
        // maxMessageChars 限制单条消息长度——system/user 输入过长会撑爆总字符预算,
        // 也可能直接被模型 API 拒绝。所有进入 conversation 的消息统一过 enforceMessageBudget。
        conversation.add(enforceMessageBudget(AgentMessage.system(systemPrompt)));
        appendConversationSeed(conversation, request);
        conversation.add(enforceMessageBudget(AgentMessage.user(request.getText())));
        Log.d(TAG, "[Engine] tools=" + tools.size() + " systemPromptChars=" + systemPrompt.length()
                + " convChars=" + estimateConversationChars(conversation)
                + " convTokens=" + estimateConversationTokens(conversation));

        Set<String> blockedCapabilities = new HashSet<>();
        int totalToolCalls = 0;
        String finalAssistantText = null;
        StopReason stopReason = StopReason.MAX_ITERATIONS;
        String stopMessage = "达到最大迭代数";
        List<ToolResult> internalResults = new ArrayList<>();

        for (int iteration = 1; iteration <= budget.getMaxIterations(); iteration++) {
            Log.d(TAG, "[Engine] --- iteration " + iteration + "/" + budget.getMaxIterations()
                    + " blocked=" + blockedCapabilities + " totalCalls=" + totalToolCalls
                    + " convChars=" + estimateConversationChars(conversation)
                    + " convTokens=" + estimateConversationTokens(conversation) + " ---");

            TaskState terminal = cancellationState(request);
            if (terminal != null) {
                stopReason = terminal == TaskState.CANCELLED
                        ? StopReason.CANCELLED : StopReason.TIMEOUT;
                stopMessage = terminal == TaskState.CANCELLED ? "任务已取消" : "任务超过截止时间";
                Log.w(TAG, "[Engine] iter " + iteration + " terminal=" + terminal + " reason=" + stopReason);
                break;
            }
            // 80% 主动触发压缩——避免被动到 100% 才触发,
            // 给本轮 LLM 调用留充足预算。100% 被动路径保留在 appendMessageWithBudget(兜底)。
            tryCompressConversation(conversation, budget, request);
            if (estimateConversationChars(conversation) > budget.getTotalInputChars()) {
                stopReason = StopReason.BUDGET_EXHAUSTED;
                stopMessage = "输入字符预算耗尽";
                Log.w(TAG, "[Engine] iter " + iteration + " budget exhausted convChars="
                        + estimateConversationChars(conversation) + " > "
                        + budget.getTotalInputChars());
                break;
            }

            // LLM 调用前 drain SteerMailbox。
            // - REPROMPT:把 payload 作为新 user message 加入 conversation,继续本轮 LLM
            // - FORCE_TOOL:跳过本轮 LLM,直接构造 ToolCall 走 Tool 执行分支(下面 forceToolTurn 分支)
            // - DEFER:StopReason.DEFERRED,跳出 Loop
            long iterationStarted = System.nanoTime();
            Steer forcedSteer = drainSteerBeforeLlm(request, conversation, iteration);
            if (forcedSteer == DEFER_SENTINEL) {
                stopReason = StopReason.DEFERRED;
                stopMessage = "用户要求推迟当前任务";
                Log.i(TAG, "[Engine] iter " + iteration + " steer DEFER -> terminal");
                trajectory.addIteration(new AgentIteration(iteration,
                        AgentMessage.assistant("[steer:defer]", Collections.<ToolCall>emptyList()),
                        Collections.<AgentIteration.ToolCallSnapshot>emptyList(),
                        Collections.<ToolObservation>emptyList(),
                        Collections.<PolicyDecision>emptyList(),
                        elapsedMillis(iterationStarted)));
                break;
            }

            ModelTurn turn;
            if (forcedSteer != null) {
                // FORCE_TOOL:跳过 LLM,直接构造一个 ModelTurn 包裹用户指定的 ToolCall
                ToolCall forcedCall = new ToolCall(forcedSteer.getPayload(), forcedSteer.getArguments());
                turn = ModelTurn.ofToolCalls(Collections.singletonList(forcedCall),
                        "[steer:force_tool]");
                Log.i(TAG, "[Engine] iter " + iteration + " steer FORCE_TOOL cap="
                        + forcedSteer.getPayload() + " -> skip LLM, execute directly");
            } else {
                ModelTurnRequest turnRequest = new ModelTurnRequest(request, conversation, tools,
                        systemPrompt, sessionContext);
                Log.d(TAG, "[Engine] iter " + iteration + " -> ModelGateway.decide()");
                ModelCallExecutor.Result result = modelCallExecutor.decide(modelGateway, turnRequest);
                if (!result.isSuccess()) {
                    stopReason = result.getTerminalReason();
                    stopMessage = result.getMessage();
                    Log.w(TAG, "[Engine] iter " + iteration + " model call terminal reason="
                            + stopReason + " msg=" + truncate(result.getMessage(), 160));
                    break;
                }
                turn = result.getTurn();
            }
            Log.d(TAG, "[Engine] iter " + iteration + " <- model turn cost="
                    + elapsedMillis(iterationStarted) + "ms");
            // 调试轨迹（评估 v1.0 §4.3）：模型建议（MODEL_PROPOSED）——模型产出的
            // 候选 tool_calls 是"建议"而非"事实"，四态语义由后续事件逐级确认
            com.matrix.agent.debugtrace.DebugTraceHolder.emit(
                    com.matrix.agent.debugtrace.DebugTraceEvent.PHASE_MODEL_PROPOSED,
                    request.getRequestId(),
                    "iter=" + iteration + " finish=" + turn.getFinishReason()
                            + " toolCalls=" + turn.getToolCalls().size()
                            + " contentChars="
                            + safeLength(turn.getAssistantMessage().getContent()));

            // assistant message 加入前同样过预算检查(单条截断 + 条数/字符上限)
            if (!appendMessageWithBudget(conversation, turn.getAssistantMessage(), request)) {
                stopReason = StopReason.BUDGET_EXHAUSTED;
                stopMessage = "加入 assistant 消息后超过字符或条数预算";
                Log.w(TAG, "[Engine] iter " + iteration + " budget exhausted on assistant append"
                        + " convChars=" + estimateConversationChars(conversation)
                        + " msgCount=" + conversation.size());
                trajectory.addIteration(new AgentIteration(iteration,
                        auditRedactAssistant(turn.getAssistantMessage()),
                        snapshots(turn.getToolCalls()), Collections.emptyList(),
                        Collections.emptyList(), elapsedMillis(iterationStarted)));
                break;
            }

            // 模型输出因 max_tokens 截断时,Observation/答复可能不完整,
            // 不能视为正常完成。无论是否伴随 tool_call 都走异常终止。
            if (turn.getFinishReason() == FinishReason.LENGTH) {
                stopReason = StopReason.LENGTH_EXCEEDED;
                stopMessage = "模型输出因 max_tokens 截断(finish_reason=length)";
                Log.w(TAG, "[Engine] iter " + iteration + " LENGTH_EXCEEDED"
                        + " contentChars=" + safeLength(turn.getAssistantMessage().getContent())
                        + " toolCalls=" + turn.getToolCalls().size());
                trajectory.addIteration(new AgentIteration(iteration,
                        auditRedactAssistant(turn.getAssistantMessage()),
                        snapshots(turn.getToolCalls()), Collections.emptyList(),
                        Collections.emptyList(), elapsedMillis(iterationStarted)));
                break;
            }

            // Provider 协议不一致(finish_reason 缺失/未知,或 STOP 但 content 空)
            // 必须终止,不能被错判为 NO_TOOL_CALL → SUCCEEDED。ModelApiClient 解析器侧已把
            // STOP + 空 content 降级为 NONE,这里见到 NONE 就走 PROTOCOL_ERROR。
            if (turn.getFinishReason() == FinishReason.NONE) {
                stopReason = StopReason.PROTOCOL_ERROR;
                stopMessage = "Provider 协议不一致(finish_reason 缺失/未知或 STOP 但 content 为空)";
                Log.w(TAG, "[Engine] iter " + iteration + " PROTOCOL_ERROR"
                        + " contentChars=" + safeLength(turn.getAssistantMessage().getContent())
                        + " toolCalls=" + turn.getToolCalls().size());
                trajectory.addIteration(new AgentIteration(iteration,
                        auditRedactAssistant(turn.getAssistantMessage()),
                        snapshots(turn.getToolCalls()), Collections.emptyList(),
                        Collections.emptyList(), elapsedMillis(iterationStarted)));
                break;
            }

            if (!turn.hasToolCalls()) {
                stopReason = StopReason.NO_TOOL_CALL;
                stopMessage = "模型选择直接答复用户";
                // 可信终答捕获：仅 NO_TOOL_CALL + 非空白 content 视为完整答复。
                // LENGTH/NONE/异常终止不捕获——截断或协议错误的文本不可冒充完整回复。
                String content = turn.getAssistantMessage().getContent();
                finalAssistantText = (content == null || content.isBlank()) ? null : content;
                Log.i(TAG, "[Engine] iter " + iteration + " no tool_call -> STOP(模型直接答复)"
                        + " contentChars=" + safeLength(turn.getAssistantMessage().getContent()));
                trajectory.addIteration(new AgentIteration(iteration,
                        auditRedactAssistant(turn.getAssistantMessage()),
                        snapshots(turn.getToolCalls()), Collections.emptyList(),
                        Collections.emptyList(), elapsedMillis(iterationStarted)));
                break;
            }

            if (totalToolCalls + turn.getToolCalls().size() > budget.getMaxToolCalls()) {
                stopReason = StopReason.MAX_TOOL_CALLS;
                stopMessage = "超过累计 Tool Call 上限";
                Log.w(TAG, "[Engine] iter " + iteration + " tool_calls overflow total="
                        + totalToolCalls + " + new=" + turn.getToolCalls().size()
                        + " > budget=" + budget.getMaxToolCalls());
                trajectory.addIteration(new AgentIteration(iteration,
                        auditRedactAssistant(turn.getAssistantMessage()),
                        snapshots(turn.getToolCalls()), Collections.emptyList(),
                        Collections.emptyList(), elapsedMillis(iterationStarted)));
                break;
            }

            // Tool 执行前显式 cancel 检查——LLM 已返回 tool_calls,
            // 但若此时外部 cancel 已触发,不进入 Tool 循环。
            TaskState preToolTerminal = cancellationState(request);
            if (preToolTerminal != null) {
                stopReason = preToolTerminal == TaskState.CANCELLED
                        ? StopReason.CANCELLED : StopReason.TIMEOUT;
                stopMessage = preToolTerminal == TaskState.CANCELLED
                        ? "任务在 Tool 执行前已取消" : "任务在 Tool 执行前超过截止时间";
                Log.w(TAG, "[Engine] iter " + iteration + " pre-tool terminal=" + preToolTerminal
                        + " reason=" + stopReason);
                trajectory.addIteration(new AgentIteration(iteration,
                        auditRedactAssistant(turn.getAssistantMessage()),
                        snapshots(turn.getToolCalls()), Collections.emptyList(),
                        Collections.emptyList(), elapsedMillis(iterationStarted)));
                break;
            }

            // Tool 执行前 drain SteerMailbox 的 DEFER 指令。
            // 这里不再处理 REPROMPT / FORCE_TOOL——模型已决策本轮 tool_calls,
            // 半路塞入会破坏一致性。仅响应 DEFER(用户要求推迟)。
            if (hasDeferredSteer(request)) {
                stopReason = StopReason.DEFERRED;
                stopMessage = "用户在 Tool 执行前要求推迟当前任务";
                Log.i(TAG, "[Engine] iter " + iteration + " pre-tool steer DEFER -> terminal");
                trajectory.addIteration(new AgentIteration(iteration,
                        auditRedactAssistant(turn.getAssistantMessage()),
                        snapshots(turn.getToolCalls()), Collections.emptyList(),
                        Collections.emptyList(), elapsedMillis(iterationStarted)));
                break;
            }

            Log.i(TAG, "[Engine] iter " + iteration + " processing "
                    + turn.getToolCalls().size() + " tool call(s)");

            List<ToolObservation> observations = new ArrayList<>();
            List<PolicyDecision> decisions = new ArrayList<>();
            // label 供 per-tool-call cancel 检查快速跳出 iteration 循环
            toolCallLoop:
            for (ToolCall call : turn.getToolCalls()) {
                // per-tool-call cancel 检查——多 tool_call 序列中,
                // 第 N 个完成后第 N+1 个开始前若 cancel 已触发,立即跳出整个 iteration 循环,
                // 不让后续 tool 继续执行(车控写操作尤其需要)。
                TaskState perToolTerminal = cancellationState(request);
                if (perToolTerminal != null) {
                    stopReason = perToolTerminal == TaskState.CANCELLED
                            ? StopReason.CANCELLED : StopReason.TIMEOUT;
                    stopMessage = perToolTerminal == TaskState.CANCELLED
                            ? "任务在第 " + totalToolCalls + " 个 Tool Call 后已取消"
                            : "任务在第 " + totalToolCalls + " 个 Tool Call 后超过截止时间";
                    Log.w(TAG, "[Engine] iter " + iteration + " per-tool terminal="
                            + perToolTerminal + " reason=" + stopReason
                            + " (跳过剩余 " + (turn.getToolCalls().size() - decisions.size())
                            + " 个 tool_call)");
                    break toolCallLoop;
                }
                totalToolCalls++;
                // Tool args 含目的地 / 联系人 / memory value,绝不原文进 logcat。
                // 旧实现直接打印 call.getArguments()——destination 等业务字段会泄漏。
                Log.d(TAG, "[Engine]   call #" + totalToolCalls
                        + " cap=" + call.getCapabilityName()
                        + " id=" + call.getStepId()
                        + " args=" + SafeLog.TOOL_ARGS_PLACEHOLDER
                        + " argKeys=" + call.getArguments().keySet());

                if (blockedCapabilities.contains(call.getCapabilityName())) {
                    String reason = "Capability 已被策略禁用:" + call.getCapabilityName();
                    Log.w(TAG, "[Engine]   -> BLOCKED (already disabled) " + call.getCapabilityName());
                    observations.add(ToolObservation.rejected(call, reason, true));
                    decisions.add(PolicyDecision.denyCapability(reason));
                    continue;
                }
                PolicyDecision decision = policyEngine.evaluate(request, call);
                decisions.add(decision);
                // 调试轨迹：策略判定（POLICY_DECIDED）——允许/拒绝与理由（Redactor 净化）。
                // 结构化键走 SDK 线格式契约；reason 是含空格的自由文本，只能作末尾记号。
                com.matrix.agent.debugtrace.DebugTraceHolder.emit(
                        com.matrix.agent.debugtrace.DebugTraceEvent.PHASE_POLICY_DECIDED,
                        request.getRequestId(),
                        DebugTracePayloads.capability(call.getCapabilityName())
                                + " " + DebugTracePayloads.token(DebugTracePayloads.KEY_ALLOWED,
                                        decision.isAllowed())
                                + " reason=" + decision.getReason());
                if (!decision.isAllowed()) {
                    boolean capabilityBlock =
                            decision.getRejectionType() == PolicyDecision.RejectionType.CAPABILITY;
                    if (capabilityBlock) {
                        blockedCapabilities.add(call.getCapabilityName());
                        Log.w(TAG, "[Engine]   -> POLICY CAPABILITY_REJECTED cap="
                                + call.getCapabilityName() + " reason=" + decision.getReason()
                                + " (now blocked for rest of loop)");
                    } else {
                        Log.i(TAG, "[Engine]   -> POLICY PARAMETER_REJECTED cap="
                                + call.getCapabilityName() + " reason=" + decision.getReason()
                                + " (model may retry with new args)");
                    }
                    // POLICY 增量事件——decision.isAllowed()==false 时落 audit_event。
                    // reason 走 AuditRedactor 字段级脱敏(避免 destination / 联系人 / memory value 泄漏)。
                    auditEventRecorder.recordPolicyDecision(request.getRequestId(),
                            ActorUsers.userIdOf(request), auditZone(request), auditActor(request),
                            call.getCapabilityName(),
                            capabilityBlock ? "CAPABILITY" : "PARAMETER",
                            auditRedactor.redactFreeText(decision.getReason()),
                            request.getEpoch());
                    observations.add(ToolObservation.rejected(call, decision.getReason(),
                            capabilityBlock));
                    continue;
                }
                Log.d(TAG, "[Engine]   -> policy ALLOW, executing provider");
                // 调试轨迹：请求已送达（REQUEST_DELIVERED）——策略已允许，即将执行
                com.matrix.agent.debugtrace.DebugTraceHolder.emit(
                        com.matrix.agent.debugtrace.DebugTraceEvent.PHASE_REQUEST_DELIVERED,
                        request.getRequestId(),
                        DebugTracePayloads.capability(call.getCapabilityName())
                                + " argumentShape="
                                + com.matrix.agent.debugtrace.DebugTraceRedactor.argumentShape(
                                        call.getArguments()));
                // PRE_TOOL 增量事件——policy ALLOW 后,toolExecutor.execute 前。
                // args 走 AuditRedactor 字段级脱敏,与 TrajectoryEntity snapshots 同保护级别。
                Map<String, Object> redactedArgsForAudit = auditRedactor.redactArguments(
                        call.getCapabilityName(), call.getArguments());
                auditEventRecorder.recordPreTool(request.getRequestId(),
                        ActorUsers.userIdOf(request), auditZone(request), auditActor(request),
                        call.getCapabilityName(), redactedArgsForAudit,
                        redactedArgsForAudit.toString(), request.getEpoch());
                CapabilityDefinition definition = policyEngine.findDefinition(call.getCapabilityName());
                ToolResult toolResult = toolExecutor.execute(provider, definition, request, call);
                // ToolResult message 是 Provider 给的诊断文本,可能含目的地 / 联系人
                // (如"导航到北京市某小区失败"),用 SafeLog 占位符包裹。status / verified / durationMs
                // 是结构性指标可保留。
                Log.d(TAG, "[Engine]   <- tool result status=" + toolResult.getStatus()
                        + " verified=" + toolResult.isVerified()
                        + " durationMs=" + toolResult.getDurationMillis()
                        + " msg=" + SafeLog.TOOL_RESULT_PLACEHOLDER);
                // 调试轨迹：设备已核验（DEVICE_VERIFIED）——工具返回的 status/verified/
                // readback 是结构化事实（消息正文不进轨迹——经 Redactor 也会截断）
                com.matrix.agent.debugtrace.DebugTraceHolder.emit(
                        com.matrix.agent.debugtrace.DebugTraceEvent.PHASE_DEVICE_VERIFIED,
                        request.getRequestId(),
                        DebugTracePayloads.capability(call.getCapabilityName())
                                + " " + DebugTracePayloads.token(DebugTracePayloads.KEY_STATUS,
                                        toolResult.getStatus())
                                + " " + DebugTracePayloads.token(DebugTracePayloads.KEY_VERIFIED,
                                        toolResult.isVerified())
                                + " durationMs=" + toolResult.getDurationMillis()
                                + " observedKeys="
                                + com.matrix.agent.debugtrace.DebugTraceRedactor.argumentShape(
                                        toolResult.getObservedState()));
                // POST_TOOL 增量事件——toolExecutor 返回后,observation 加入前。
                // result message 走 AuditRedactor 文本脱敏(避免"导航到 XX 失败"等业务字段泄漏)。
                auditEventRecorder.recordPostTool(request.getRequestId(),
                        ActorUsers.userIdOf(request), auditZone(request), auditActor(request),
                        call.getCapabilityName(),
                        toolResult.getStatus().name(),
                        toolResult.isVerified(),
                        toolResult.getDurationMillis(),
                        auditRedactor.redactFreeText(toolResult.getMessage()),
                        request.getEpoch());
                if (definition.isVerificationRequired() && toolResult.isSuccess()
                        && !toolResult.isVerified()) {
                    Log.w(TAG, "[Engine]   verification required but readback unverified -> VERIFICATION_FAILED");
                    toolResult = new ToolResult(ToolResult.Status.VERIFICATION_FAILED,
                            toolResult.getCapabilityName(), "Capability 未通过强制回读验证",
                            toolResult.getObservedState(), false, toolResult.getDurationMillis());
                }
                observations.add(ToolObservation.of(call, toolResult));
                contextUpdater.onToolCompleted(sessionContext, call, toolResult);
            }

            // 数据边界(修复后的正确方向):
            // - Conversation(喂模型):ModelSanitizer 只脱凭据,保留 memory.preference.* 真实 value
            //   等任务语义,否则模型答不出"我喜欢多少度"。
            // - Trajectory(UI/审计):AuditRedactor 字段级脱敏——memory preference value → <memory>。
            // 每条 tool message 加入前过预算检查(单条截断 + 条数/字符上限)。
            boolean budgetExhausted = false;
            for (ToolObservation observation : observations) {
                ToolObservation sanitized = modelSanitizer.sanitize(observation);
                AgentMessage toolMsg = sanitized.toToolMessage();
                if (!appendMessageWithBudget(conversation, toolMsg, request)) {
                    Log.w(TAG, "[Engine] iter " + iteration + " budget exhausted on tool message"
                            + " cap=" + observation.getCapabilityName()
                            + " convChars=" + estimateConversationChars(conversation)
                            + " msgCount=" + conversation.size());
                    budgetExhausted = true;
                    break;
                }
            }

            // 内部可信域:累积原始 ToolResult——业务调用方通过 AgentOutcome.getInternalResults() 取真实值,
            // 不被 audit 占位符(<memory>)影响。
            for (ToolObservation observation : observations) {
                if (observation.getResult() != null) {
                    internalResults.add(observation.getResult());
                } else {
                    internalResults.add(ToolResult.rejected(
                            observation.getCapabilityName(), observation.getRejectionReason()));
                }
            }

            List<ToolObservation> auditObservations = new ArrayList<>(observations.size());
            for (ToolObservation observation : observations) {
                auditObservations.add(auditRedactor.redact(observation));
            }
            AgentMessage auditAssistant = auditRedactAssistant(turn.getAssistantMessage());
            trajectory.addIteration(new AgentIteration(iteration, auditAssistant,
                    snapshots(turn.getToolCalls()), auditObservations, decisions,
                    elapsedMillis(iterationStarted)));
            Log.d(TAG, "[Engine] iter " + iteration + " observations=" + observations.size()
                    + " conversation=sanitized trajectory=audit-redacted");
            if (budgetExhausted) {
                stopReason = StopReason.BUDGET_EXHAUSTED;
                stopMessage = "加入 tool observation 后超过字符或条数预算";
                break;
            }
        }

        trajectory.finish(stopReason, elapsedMillis(started), totalToolCalls);
        TaskState finalState = computeFinalState(trajectory, stopReason);
        // 调试轨迹：轮次结束（ROUND_END）——主循环正常出口
        com.matrix.agent.debugtrace.DebugTraceHolder.emit(
                com.matrix.agent.debugtrace.DebugTraceEvent.PHASE_ROUND_END,
                request.getRequestId(),
                "stopReason=" + stopReason + " toolCalls=" + totalToolCalls
                        + " finalState=" + finalState
                        + " durationMs=" + elapsedMillis(started));
        Log.i(TAG, "[Engine] END req=" + request.getRequestId()
                + " finalState=" + finalState
                + " stopReason=" + stopReason
                + " iterations=" + trajectory.getIterations().size()
                + " totalToolCalls=" + totalToolCalls
                + " successToolCalls=" + trajectory.countSuccessfulToolCalls()
                + " durationMs=" + elapsedMillis(started)
                + " msg=\"" + truncate(stopMessage, 120) + "\"");
        // session turn 字符串原保留完整用户输入,改用 SafeLog 占位符。
        // SessionContext 仍保留语义(USER / RESULT / STOP),但不存原文。
        sessionContext.addTurn("USER: " + SafeLog.USER_INPUT_PLACEHOLDER
                + " | RESULT: " + finalState + " | STOP: " + stopReason
                + " | " + stopMessage);
        AgentOutcome mainOutcome = new AgentOutcome(request.getRequestId(), finalState, stopReason,
                trajectory, elapsedMillis(started), internalResults, finalAssistantText);
        // 出口点 4——主路径终态(SUCCEEDED/FAILED/PARTIALLY/CANCELLED/TIMED_OUT 等)
        auditSink.persist(mainOutcome, request);
        // Episodic 自动写入——任务终态后写 session_history 表,
        // 供 EpisodicMemorySourceImpl 召回。fail-log(异常仅 Log.w,不向上传播)。
        // 传 request.getEpoch(),RoomMemoryWriter 在 Room 事务内做 epoch gate。
        memoryWriter.writeEpisodicOnTerminal(request, mainOutcome, request.getEpoch());
        return mainOutcome;
    }

    /**
     * 按 {@link StopReason} 先判终止性质,再算最终状态:
     * <ul>
     *   <li>{@code DONE} / {@code NO_TOOL_CALL}:正常结束,按 Tool 结果分 SUCCEEDED / PARTIALLY / FAILED。</li>
     *   <li>{@code CANCELLED} / {@code TIMEOUT}:直接对应 TaskState。</li>
     *   <li>{@code MAX_ITERATIONS} / {@code MAX_TOOL_CALLS} / {@code BUDGET_EXHAUSTED} / {@code POLICY_HALT}:
     *       异常终止——有部分成功结果最多 PARTIALLY_SUCCEEDED,**永远不能 SUCCEEDED**。
     *       否则失控循环(模型一直调成功查询 Tool 直到耗尽预算)会被错判为成功。</li>
     * </ul>
     */
    private static TaskState computeFinalState(Trajectory trajectory, StopReason stopReason) {
        if (stopReason == StopReason.CANCELLED) return TaskState.CANCELLED;
        if (stopReason == StopReason.TIMEOUT) return TaskState.TIMED_OUT;
        // 用户推迟语义——不是失败,被推迟的任务可被重新调度。
        if (stopReason == StopReason.DEFERRED) return TaskState.DEFERRED;

        int successCount = trajectory.countSuccessfulToolCalls();
        int total = trajectory.getTotalToolCalls();
        boolean normalStop = stopReason == StopReason.DONE || stopReason == StopReason.NO_TOOL_CALL;

        if (normalStop) {
            if (total == 0) return TaskState.SUCCEEDED;
            if (successCount == 0) return TaskState.FAILED;
            return countHardFailures(trajectory) == 0
                    ? TaskState.SUCCEEDED
                    : TaskState.PARTIALLY_SUCCEEDED;
        }
        // 异常终止:即使有部分成功结果也不能算 SUCCEEDED
        return successCount == 0 ? TaskState.FAILED : TaskState.PARTIALLY_SUCCEEDED;
    }

    /**
     * Audit Assistant:content 走文本 redact,ToolCall 的 arguments 走字段级 redactArguments。
     *
     * <p>旧实现只 redact content,ToolCall 列表原样复制——
     * Trajectory 中 ToolCallSnapshot.arguments 已脱敏,但 AssistantMessage.toolCalls.arguments
     * 是原始值,导致同一 Trajectory 同时存在脱敏与未脱敏两份参数。这里把 ToolCall 也重新构造,
     * 保留 stepId 和 capabilityName(协议层 ID 关联不能丢),但 arguments 全部脱敏。
     *
     * <p>用 schema-aware 版本 {@link AuditRedactor#redactArguments(String, Map)}
     * 让 navigation.destination / memory.preference.value 等业务敏感字段也走 capability
     * schema 脱敏。
     */
    private AgentMessage auditRedactAssistant(AgentMessage original) {
        if (original == null) return null;
        // assistant content 是模型自由文本,凭据正则识别不出业务 PII
        // (地址 / 联系人 / 电话) ——审计侧用 redactFreeText 长度 + SHA-8 摘要替代原文,
        // 保留诊断信号 (chars) 与事后比对能力 (sha8),但不可逆推原文。
        // ToolCall arguments 不动,继续走 schema-aware redactArguments(cap, args)。
        String redacted = auditRedactor.redactFreeText(original.getContent());
        List<ToolCall> redactedCalls;
        if (original.getToolCalls() == null || original.getToolCalls().isEmpty()) {
            redactedCalls = Collections.emptyList();
        } else {
            redactedCalls = new ArrayList<>(original.getToolCalls().size());
            for (ToolCall call : original.getToolCalls()) {
                redactedCalls.add(ToolCall.withId(call.getStepId(), call.getCapabilityName(),
                        auditRedactor.redactArguments(call.getCapabilityName(), call.getArguments())));
            }
        }
        return AgentMessage.assistant(redacted, redactedCalls);
    }

    /**
     * 硬失败:执行失败 / 验证失败 / Capability 被禁用。PARAMETER 拒绝不计入硬失败,
     * 因为模型可在剩余预算内换参数重试——只要后续有成功执行,前一次的 PARAMETER 拒绝
     * 不应把整个任务拖到 PARTIALLY_SUCCEEDED。
     */
    private static int countHardFailures(Trajectory trajectory) {
        int count = 0;
        for (AgentIteration iteration : trajectory.getIterations()) {
            for (ToolObservation observation : iteration.getObservations()) {
                if (observation.getResult() != null) {
                    if (!observation.getResult().isSuccess()) count++;
                } else if (observation.isCapabilityBlocked()) {
                    count++;
                }
            }
        }
        return count;
    }

    private static TaskState cancellationState(AgentRequest request) {
        if (request.isCancelled()) return TaskState.CANCELLED;
        if (request.isTimedOut()) return TaskState.TIMED_OUT;
        return null;
    }

    /**
     * terminal outcome 改为 instance method——
     * 内部接入 {@link #auditSink}.persist(5 个出口点中的 2 个由本方法覆盖)。
     *
     * <p>原是 static,旧测试若直接调 static 版本会编译失败——保留 static bridge
     * delegate 到 instance 版本(兼容契约)。
     */
    private AgentOutcome terminalOutcome(AgentRequest request, TaskState state,
            StopReason reason, String message, long started) {
        Trajectory trajectory = new Trajectory();
        // 调试轨迹：轮次结束（ROUND_END）——terminalOutcome 路径（提前终止）
        com.matrix.agent.debugtrace.DebugTraceHolder.emit(
                com.matrix.agent.debugtrace.DebugTraceEvent.PHASE_ROUND_END,
                request.getRequestId(),
                "stopReason=" + reason + " durationMs=" + elapsedMillis(started)
                        + " terminal=" + state);
        trajectory.finish(reason, elapsedMillis(started), 0);
        AgentOutcome outcome = new AgentOutcome(request.getRequestId(), state, reason, trajectory,
                elapsedMillis(started));
        auditSink.persist(outcome, request);
        // Episodic 自动写入——TIMEOUT/CANCELLED/PREEMPTED 终态也写
        // session_history 表(供 Episodic 召回失败/取消的历史)。fail-log。
        // 传 request.getEpoch(),RoomMemoryWriter 在 Room 事务内做 epoch gate。
        memoryWriter.writeEpisodicOnTerminal(request, outcome, request.getEpoch());
        return outcome;
    }

    private String buildSystemPrompt(AgentRequest request) {
        return promptContextAssembler.build(request);
    }

    /**
     * 种子注入：装配器已保证内容安全与预算；此处仅做防御性兜底——
     * 逐条过单条上限，且一旦“种子 + 预估 user 文本”将触及总量预算即停止追加，
     * 保证当轮用户指令的优先级（设计文档 §5.4-5）。
     */
    private void appendConversationSeed(List<AgentMessage> conversation,
            com.matrix.agent.identity.AgentRequest request) {
        com.matrix.agent.contract.ConversationSeedContext seed = request.getConversationSeed();
        if (seed == null || seed.isEmpty()) {
            return;
        }
        int userEstimate = request.getText() == null ? 0 : request.getText().length();
        int appended = 0;
        for (com.matrix.agent.contract.AgentMessage message : seed.messages()) {
            AgentMessage bounded = enforceMessageBudget(message);
            if (estimateConversationChars(conversation) + bounded.estimateChars() + userEstimate
                    > budget.getTotalInputChars()) {
                Log.w(TAG, "[Engine] 种子预算兜底触发：跳过剩余 " + (seed.messages().size() - appended)
                        + " 条种子消息（当前指令优先）");
                break;
            }
            conversation.add(bounded);
            appended++;
        }
        if (appended > 0) {
            Log.d(TAG, "[Engine] 注入对话种子 messages=" + appended + "/" + seed.messages().size());
        }
    }

    private static int estimateConversationChars(List<AgentMessage> conversation) {
        int total = 0;
        for (AgentMessage message : conversation) total += message.estimateChars();
        return total;
    }

    /**
     * conversation 累计 token 估算——双轨统计用,**不参与 budget 决策**。
     *
     * <p>tokenizer=null 时退化为 char/4 估算(与旧版一致);AppContainer 注入 JtokkitTokenizer
     * 后真实 BPE 估算。仅用于日志输出,为主路径切换提供精度数据。
     */
    private int estimateConversationTokens(List<AgentMessage> conversation) {
        if (tokenizer == null) {
            return Math.max(1, estimateConversationChars(conversation)
                    / AgentBudget.CHAR_PER_TOKEN_FALLBACK);
        }
        int total = 0;
        for (AgentMessage message : conversation) {
            total += tokenizer.count(message.getContent());
        }
        return total;
    }

    /**
     * 统一消息入预算入口。
     *
     * <p>单条消息超过 {@code maxMessageChars} → 截断(保留 role / toolCalls,只截 content)。
     * 用于 system/user 等首条加入 conversation 的消息。
     */
    private AgentMessage enforceMessageBudget(AgentMessage message) {
        if (message == null) return null;
        String content = message.getContent();
        if (content == null || content.length() <= budget.getMaxMessageChars()) return message;
        String truncated = ModelSanitizer.truncateWithSuffix(content, budget.getMaxMessageChars());
        Log.w(TAG, "[Engine] message truncated role=" + message.getRole()
                + " origChars=" + content.length()
                + " truncatedChars=" + truncated.length()
                + " maxMessageChars=" + budget.getMaxMessageChars());
        switch (message.getRole()) {
            case ASSISTANT:
                return AgentMessage.assistant(truncated, message.getToolCalls());
            case TOOL:
                return AgentMessage.tool(message.getToolCallId(), message.getToolName(), truncated);
            case USER:
                return AgentMessage.user(truncated);
            case SYSTEM:
            default:
                return AgentMessage.system(truncated);
        }
    }

    /**
     * 每次迭代主动检查 80% 阈值,压缩 conversation。
     *
     * <p>与 {@link #appendMessageWithBudget} 内的 100% 被动压缩互补:
     * <ul>
     *   <li>主动 80%:预算真正耗尽前介入,本轮 LLM 调用还有充足预算;</li>
     *   <li>被动 100%:race 兜底(模型生成期间 turns 突然变多 / 单条极大消息)。</li>
     * </ul>
     */
    private void tryCompressConversation(List<AgentMessage> conversation, AgentBudget budget,
            AgentRequest request) {
        if (conversationCompressor == null || conversation.isEmpty()) return;
        int totalChars = estimateConversationChars(conversation);
        int triggerThreshold = (int) (budget.getTotalInputChars() * 0.8);
        if (totalChars <= triggerThreshold) return;
        Log.i(TAG, "[Engine] active compression trigger convChars=" + totalChars
                + " threshold=" + triggerThreshold);
        List<AgentMessage> compressed = conversationCompressor.compress(conversation, budget, request);
        if (compressed != conversation && compressed.size() < conversation.size()) {
            int beforeChars = totalChars;
            conversation.clear();
            conversation.addAll(compressed);
            Log.i(TAG, "[Engine] active compression done beforeMsgs/Chars=" + beforeChars
                    + " afterMsgs=" + conversation.size()
                    + " afterChars=" + estimateConversationChars(conversation));
        }
    }

    /**
     * 把消息加入 conversation 前检查所有预算维度。
     *
     * <p>返回 true 表示已加入;返回 false 表示**未加入**且应当走 BUDGET_EXHAUSTED 终止。
     * 检查顺序:
     * <ol>
     *   <li>单条消息字符上限(超 → 先截断再继续后续检查);</li>
     *   <li>消息条数上限 {@code maxMessageCount}(超 → 拒绝加入);</li>
     *   <li>conversation 累计字符上限 {@code totalInputChars}(超 → 拒绝加入)。</li>
     * </ol>
     */
    private boolean appendMessageWithBudget(List<AgentMessage> conversation, AgentMessage message,
            AgentRequest request) {
        AgentMessage truncated = enforceMessageBudget(message);
        if (conversation.size() + 1 > budget.getMaxMessageCount()) {
            Log.w(TAG, "[Engine] maxMessageCount exceeded currentCount=" + conversation.size()
                    + " + 1 > budget=" + budget.getMaxMessageCount());
            return false;
        }
        int projected = estimateConversationChars(conversation) + truncated.estimateChars();
        if (projected > budget.getTotalInputChars()) {
            // ConversationCompressor 注入后,先尝试压缩旧 turns 腾预算。
            // 压缩成功后重试 append;仍超才返回 false(BUDGET_EXHAUSTED)。
            if (conversationCompressor != null && !conversation.isEmpty()) {
                List<AgentMessage> compressed = conversationCompressor.compress(
                        conversation, budget, request);
                if (compressed != conversation && compressed.size() < conversation.size()) {
                    // 原地替换 conversation 内容(保持 list 引用不变,Loop 内可见)
                    conversation.clear();
                    conversation.addAll(compressed);
                    int recomputed = estimateConversationChars(conversation) + truncated.estimateChars();
                    if (recomputed <= budget.getTotalInputChars()
                            && conversation.size() + 1 <= budget.getMaxMessageCount()) {
                        Log.i(TAG, "[Engine] post-compression retry append convChars="
                                + estimateConversationChars(conversation)
                                + " projected=" + recomputed
                                + " turns=" + conversation.size());
                        conversation.add(truncated);
                        return true;
                    }
                    Log.w(TAG, "[Engine] post-compression still over budget projected="
                            + recomputed + " > budget=" + budget.getTotalInputChars());
                }
            }
            Log.w(TAG, "[Engine] totalInputChars exceeded projected=" + projected
                    + " > budget=" + budget.getTotalInputChars());
            return false;
        }
        conversation.add(truncated);
        return true;
    }

    /**
     * Trajectory 中 ToolCallSnapshot 的 arguments 必须过 AuditRedactor 字段级脱敏——
     * 否则导航 destination / 联系人 / memory save 的 value 会原文进 UI/Log。
     */
    private List<AgentIteration.ToolCallSnapshot> snapshots(List<ToolCall> calls) {
        if (calls.isEmpty()) return Collections.emptyList();
        List<AgentIteration.ToolCallSnapshot> list = new ArrayList<>(calls.size());
        for (ToolCall call : calls) {
            list.add(new AgentIteration.ToolCallSnapshot(call.getStepId(),
                    call.getCapabilityName(),
                    auditRedactor.redactArguments(call.getCapabilityName(), call.getArguments())));
        }
        return list;
    }

    private static int safeLength(String value) {
        return value == null ? 0 : value.length();
    }

    private static String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    /**
     * Loop 内部 Sentinel——drainSteerBeforeLlm 看到DEFER 时返回此常量,
     * 让 caller 用 == 比较(不与 null / 真 Steer 实例混淆)。
     */
    private static final Steer DEFER_SENTINEL = Steer.defer();

    /**
     * Tool 执行前检查 mailbox 中是否有 DEFER。
     *
     * <p>旧实现调 drain() 把整个队列掏空,即使无 DEFER,REPROMPT/FORCE_TOOL 也被永久丢弃
     * (下一轮 drainSteerBeforeLlm 再 drain 已空)。peek 只看不动,保留非 DEFER 指令给下一轮处理。
     */
    private boolean hasDeferredSteer(AgentRequest request) {
        if (steerMailbox == null) return false;
        boolean deferred = steerMailbox.peekDeferred(request.getSessionId());
        if (deferred) {
            Log.i(TAG, "[Engine] pre-tool DEFER detected, non-DEFER steers retained for next drain");
        }
        return deferred;
    }

    /**
     * 在 LLM 调用前 drain SteerMailbox。
     *
     * <p>语义:
     * <ul>
     *   <li>无 mailbox 或无 Steer → 返回 null(走正常 LLM 路径);</li>
     *   <li>{@link Steer.Type#REPROMPT} → 把 payload 作为 user message 加入 conversation,
     *       继续本轮 LLM,返回 null(让 caller 调 LLM);</li>
     *   <li>{@link Steer.Type#FORCE_TOOL} → 返回该 Steer,caller 据此跳过 LLM 直接构造 ToolCall;</li>
     *   <li>{@link Steer.Type#DEFER} → 立即返回 {@link #DEFER_SENTINEL},caller 据此走 DEFERRED 终态。</li>
     * </ul>
     *
     * <p>批量 drain 时多个同类型 Steer 按 FIFO 处理;DEFER 优先(一旦见到立即返回,
     * 后续 Steer 不消费)。REPROMPT 全部加入 conversation,FORCE_TOOL 只取最后一个
     * (用户多次"强制"应保留最新意图)。
     */
    private Steer drainSteerBeforeLlm(AgentRequest request, List<AgentMessage> conversation,
            int iteration) {
        if (steerMailbox == null) return null;
        // epoch-gated drain——stamped.epoch < request.getEpoch() 的 Steer
        // 被 drop + audit(STEER_DROPPED_STALE);AgentRequest.epoch 是 Repository.execute
        // 时注入的 memoryStore.currentEpoch()——clearUserData 后旧 epoch 的 Steer 自动失效。
        List<Steer> steers = steerMailbox.drain(request.getSessionId(), request.getEpoch());
        if (steers.isEmpty()) return null;

        Steer lastForceTool = null;
        for (Steer steer : steers) {
            Log.i(TAG, "[Engine] iter " + iteration + " drain steer type=" + steer.getType()
                    + " payloadChars=" + safeLength(steer.getPayload()));
            // STEER 增量事件——只记 type + payloadChars,
            // 不记 payload 内容(REPROMPT payload 可能含用户输入 / 联系人,有 PII 风险)。
            if (steer.getType() == Steer.Type.FORCE_TOOL || steer.getType() == Steer.Type.DEFER) {
                auditEventRecorder.recordSteer(request.getRequestId(),
                        ActorUsers.userIdOf(request), auditZone(request), auditActor(request),
                        steer.getType().name(), safeLength(steer.getPayload()), request.getEpoch());
            }
            switch (steer.getType()) {
                case REPROMPT:
                    if (!appendMessageWithBudget(conversation, AgentMessage.user(steer.getPayload()), request)) {
                        Log.w(TAG, "[Engine] iter " + iteration
                                + " REPROMPT 加入失败(预算耗尽),忽略");
                    } else {
                        Log.i(TAG, "[Engine] iter " + iteration
                                + " REPROMPT 已合并到 conversation chars="
                                + steer.getPayload().length());
                    }
                    break;
                case FORCE_TOOL:
                    lastForceTool = steer;
                    break;
                case DEFER:
                    return DEFER_SENTINEL;
                default:
                    Log.w(TAG, "[Engine] iter " + iteration + " unknown steer type " + steer.getType());
            }
        }
        return lastForceTool;
    }

    /** audit_event 行 zone 列——与历史行一致用 Actor.name() 大写。 */
    private static String auditZone(AgentRequest request) {
        return request.getActor() == null ? "" : request.getActor().name();
    }

    private static String auditActor(AgentRequest request) {
        return request.getActor() == null ? "" : request.getActor().name();
    }
}
