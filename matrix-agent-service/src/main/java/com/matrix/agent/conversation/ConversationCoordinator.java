package com.matrix.agent.conversation;

import android.util.Log;

import com.matrix.agent.api.common.MatrixErrorCode;
import com.matrix.agent.api.conversation.ConversationMessage;
import com.matrix.agent.conversation.ConversationDomain.ConversationInputChannel;
import com.matrix.agent.conversation.ConversationDomain.PersistedMessageStatus;
import com.matrix.agent.conversation.ConversationStore.ConversationRow;
import com.matrix.agent.conversation.ConversationStore.MessagePage;
import com.matrix.agent.conversation.ConversationStore.MessageRow;
import com.matrix.agent.conversation.ConversationStore.SteerSubmission;
import com.matrix.agent.conversation.ConversationStore.SubmittedSteerMessage;
import com.matrix.agent.conversation.ConversationStore.SubmittedUserMessage;
import com.matrix.agent.conversation.ConversationStore.TerminalWrite;
import com.matrix.agent.conversation.ConversationStore.UserSubmission;
import com.matrix.agent.identity.Actor;
import com.matrix.agent.task.conversation.ConversationHistorySource.HistoryEntry;
import com.matrix.agent.identity.CancellationToken;
import com.matrix.agent.identity.InputSource;
import com.matrix.agent.platform.KeyedSerialDispatcher;
import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.task.TaskState;
import com.matrix.agent.task.conversation.AssistantReply;
import com.matrix.agent.task.conversation.ConversationAssistantProjector;
import com.matrix.agent.task.conversation.ConversationTaskSubmitter;
import com.matrix.agent.task.steer.Steer;

import java.util.ArrayList;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.UnaryOperator;

/**
 * 对话协调器（设计文档 §4.3）：三通道输入的唯一业务落点。
 *
 * <p>提交路径 = 纯准备（task 端口快照）→ 原子事务（幂等判重 + sequence 分配 +
 * 用户消息 ACCEPTED + task link）→ keyed lane 预约，三者构成 SubmissionReceipt；
 * 执行路径在 lane 出队后串行：markRunning → repository.executePrepared
 * （deadline 从出队起算，排队不烧预算）→ 投影 AssistantReply → writeTerminal → 订阅事件。
 * 派发拒绝、执行异常、取消、写未知全部收敛为忠实终态；任何路径都不遗留
 * ACCEPTED/RUNNING 悬挂（进程死亡由 ConversationRecoveryCoordinator 兜底）。</p>
 */
public final class ConversationCoordinator {

    private com.matrix.agent.diagnostics.HandoffDiagnostics handoffDiagnostics =
            com.matrix.agent.diagnostics.HandoffDiagnostics.NONE;
    public void setHandoffDiagnostics(com.matrix.agent.diagnostics.HandoffDiagnostics diagnostics) {
        handoffDiagnostics = diagnostics;
    }
    private com.matrix.agent.handoff.HandoffContextRegistry handoffContexts;
    public void setHandoffContexts(com.matrix.agent.handoff.HandoffContextRegistry contexts) {
        this.handoffContexts = contexts;
    }

    private static final String TAG = "MatrixAgent";
    /** Binder 正文上限与 AgentRequest.TEXT_MAX 对齐；Host 侧再校验，不信任客户端。 */
    public static final int TEXT_MAX_CHARS = 4096;
    /** REPROMPT 追加文本沿用既有 Steer 上限语义。 */
    public static final int APPEND_MAX_CHARS = 512;

    /** 标题上限（评估 v1.0 §4.1；与 Operit 40 字符 sanitize 对齐的车机展示预算）。 */
    public static final int TITLE_MAX_CHARS = 40;

    /** 个人备注上限（评估 v1.0 §4.4 收藏 P1；展示元数据，不入上下文）。 */
    public static final int NOTE_MAX_CHARS = 200;
    private static final int WIRE_SCHEMA_VERSION = 2;

    /** 订阅事件端口；host/rpc 桥接 Binder callback，域内默认 no-op。 */
    public interface Listener {
        default void onMessageUpsert(MessageRow row) { }
        default void onMessageStatusChanged(String conversationId, String messageId,
                int statusWire, int failureCode) { }
    }

    /** 仅进程内的终态观察口。Binder 订阅与语音 TTS 回注各自独立，避免单 Listener 争夺权威。 */
    @FunctionalInterface
    public interface TerminalListener {
        void onTerminal(String conversationTaskId, AgentOutcome outcome);
    }

    /** 执行端口：AppContainer 注入 repository::executePrepared；测试注入行为桩。 */
    @FunctionalInterface
    public interface TaskExecutor {
        AgentOutcome execute(ConversationTaskSubmitter.PreparedTask task,
                CancellationToken token);
    }

    private final ConversationStore store;
    private final ConversationTaskSubmitter submitter;
    private final TaskExecutor executor;
    private final KeyedSerialDispatcher dispatcher;
    /** conv:<id>:<actor>:<zone> 派生（Host 装配注入；追加 steer 按 conversation 定位 session）。 */
    private final UnaryOperator<String> sessionForConversation;
    /**
     * steer 落点（AppContainer 注入 repository::offerSteer）。
     * 确认式（评估 v1.0 §4.3）：返回 false = 宿主队列不可用/拒绝投递，
     * 调用方据此把附属输入自收敛 FAILED（“未能并入宿主请求”）。
     */
    public interface SteerSink {
        boolean offerSteer(String sessionId, Steer steer);
    }
    private final SteerSink steerSink;
    /**
     * 受控附件端口（输入交互增强 I6）。null = 未装配（附件域降级），提交带附件 id
     * 直接 IllegalArgumentException（stub 映射 INVALID_ARGUMENT）；装配后负责：
     * 提交前验证（READY/归属/数量）+ 受限文本投影并入 AgentRequest。
     */
    public interface AttachmentPort {
        /** 验证提交附件集合：任一不存在/FAILED/越权/超量即抛 IllegalArgumentException。 */
        void validateForSubmission(String ownerUserId, String vehicleZone,
                String conversationId, List<String> attachmentIds);

        /** READY 附件的受限文本投影（经 ModelSanitizer）；无可用附件返回空串。 */
        String projectContext(String ownerUserId, String vehicleZone,
                String conversationId, List<String> attachmentIds);
    }
    private volatile AttachmentPort attachmentPort;
    /**
     * 终态轮次通知（评估 v1.0 §4.1 自动标题触发）。lane 线程回调，实现方自行异步；
     * 默认 no-op（未装配即不生成标题，不影响任何执行路径）。
     */
    public interface TerminalRoundSink {
        void onTerminalRound(String conversationId, String userMessageId, int statusWire);
    }
    private volatile TerminalRoundSink terminalRoundSink = (conv, msg, status) -> { };
    /**
     * 运行阶段追踪（输入交互增强 I3）。null = 未装配（JVM 测试/降级），行为与
     * 端口引入前一致。装配后：受理事务成功 → bind + 发布 QUEUED；终态清理 → clear。
     */
    private volatile ConversationRuntimeStageRegistry stageRegistry;
    private volatile ConversationTaskProgressBridge progressBridge;
    private final ConcurrentHashMap<String, CancellationToken> activeTokens =
            new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<TerminalListener> terminalListeners =
            new CopyOnWriteArrayList<>();
    private volatile Listener listener = new Listener() { };

    public ConversationCoordinator(ConversationStore store,
            ConversationTaskSubmitter submitter, TaskExecutor executor,
            KeyedSerialDispatcher dispatcher,
            UnaryOperator<String> sessionForConversation,
            SteerSink steerSink) {
        this.store = Objects.requireNonNull(store, "store");
        this.submitter = Objects.requireNonNull(submitter, "submitter");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.sessionForConversation = Objects.requireNonNull(sessionForConversation,
                "sessionForConversation");
        this.steerSink = Objects.requireNonNull(steerSink, "steerSink");
    }

    /** 附件端口装配（I6）；未装配时提交附件 id 会被显式拒绝。 */
    public void setAttachmentPort(AttachmentPort port) {
        this.attachmentPort = port;
    }

    public void setListener(Listener value) {
        this.listener = (value == null) ? new Listener() { } : value;
    }

    /** 自动标题等终态观察者装配（评估 v1.0 §4.1）；未装配为 no-op。 */
    public void setTerminalRoundSink(TerminalRoundSink sink) {
        this.terminalRoundSink = sink == null ? (conv, msg, status) -> { } : sink;
    }

    /** 运行阶段追踪装配（I3）；null 或未调用 = 不发布任何阶段。 */
    public void setProgressTracking(ConversationRuntimeStageRegistry registry,
            ConversationTaskProgressBridge bridge) {
        this.stageRegistry = registry;
        this.progressBridge = bridge;
    }

    public ConversationStore store() {
        return store;
    }

    /** 注册后返回可关闭句柄；用于 VoiceBridge 生命周期解绑，不与 Binder listener 混用。 */
    public AutoCloseable addTerminalListener(TerminalListener value) {
        Objects.requireNonNull(value, "value");
        terminalListeners.add(value);
        return () -> terminalListeners.remove(value);
    }

    // ---------------------------------------------------------------- 线程与读取

    public ConversationRow createConversation(String ownerUserId, String vehicleZone,
            String title) {
        return store.createConversation(new ConversationStore.NewConversation(
                ConversationIds.newConversationId(), ownerUserId, vehicleZone, title,
                WIRE_SCHEMA_VERSION));
    }

    public List<ConversationRow> listConversations(String ownerUserId, boolean includeArchived,
            int limit) {
        return store.listConversations(ownerUserId, includeArchived, limit);
    }

    public MessagePage pageMessages(String conversationId, long beforeSequenceExclusive,
            int limit) {
        return store.pageMessages(conversationId, beforeSequenceExclusive, limit);
    }

    public List<MessageRow> latestMessages(String conversationId, int limit) {
        return store.latestMessages(conversationId, limit);
    }

    // ---------------------------------------------------------------- 文字提交

    /** sendText 的域入口；幂等命中返回既有事实（replay=true），不二次执行。 */
    public TextAccepted submitText(TextCommand command) {
        Objects.requireNonNull(command, "command");
        ConversationIds.requireLowerUuid(command.conversationId(), "conversationId");
        ConversationIds.requireLowerUuid(command.clientOperationId(), "clientOperationId");
        List<String> attachmentIds = normalizedAttachmentIds(command);
        String text = normalizeSubmissionText(command.text(), TEXT_MAX_CHARS,
                !attachmentIds.isEmpty());
        String languageTag = normalizeLanguage(command.languageTag());

        String conversationTaskId = ConversationIds.newConversationTaskId();
        String runtimeRequestId = ConversationIds.newRuntimeRequestId();
        String userMessageId = ConversationIds.newMessageId();
        InputMetadata metadata = command.metadata() == null
                ? InputMetadata.text(command.conversationId(), command.clientOperationId())
                : command.metadata();
        String idempotencyKey = metadata.idempotencyKey();

        // 引用是提交的结构化元数据，不得在用户消息已入库后才发现跨会话/不存在。
        // 否则 Binder 得到 INVALID_ARGUMENT 时会遗留一条永远不入队的 ACCEPTED 行。
        ConversationStore.MessageRow quoted = null;
        if (command.quotedMessageId() != null) {
            quoted = store.findMessage(command.quotedMessageId());
            if (quoted == null || !quoted.conversationId().equals(command.conversationId())) {
                throw new IllegalArgumentException(
                        "被引消息不存在或不属于该会话: " + command.quotedMessageId());
            }
        }

        // 附件（I6 §9.2）：任何持久化动作之前完成验证——不存在/FAILED/越权/超量
        // 都必须在创建任务前拒绝（§3.2：整次提交在创建任务前拒绝，不留半写）。
        // 验证通过后立即物化受限文本投影：此后模型看到的上下文即固定，附件删除
        // （被并发抢占）不影响已受理轮次。
        String attachmentContext = projectAttachments(command, attachmentIds);

        // AgentRequest 文本 = 用户原文 + 附件投影（展示层/持久层仍是原文——
        // conversation_message.text 不被附件内容污染）。
        String agentText = attachmentContext.isEmpty()
                ? text : text + attachmentContext;

        // 1) 纯准备：只固化分类快照。历史种子必须在 keyed lane 真正出队时装配，
        // 才能看到同一 conversation 的前一条终态，且不会把排队时间烧进 Agent budget。
        // 注意：分类快照仍基于用户原文——附件资料不参与意图分类与抢占判定。
        ConversationTaskSubmitter.PreparedTask prepared = submitter.prepare(
                new ConversationTaskSubmitter.SubmitInput(
                        conversationTaskId, runtimeRequestId, command.conversationId(), agentText,
                        command.actor(), command.agentSessionId(), command.arbitrationKey(),
                        metadata.inputSource(), languageTag, metadata.asrConfidence(),
                        metadata.confidenceAvailable()));

        // 2) 原子事务：幂等判重 + sequence + 用户消息(ACCEPTED) + task link + 附件冻结。
        SubmittedUserMessage submitted = store.submitUserMessage(new UserSubmission(
                command.conversationId(), userMessageId,
                channelWire(metadata.channel()), text, languageTag,
                conversationTaskId, runtimeRequestId,
                prepared.classification().readOnlyHint(), idempotencyKey,
                command.submittedDraftInstanceId(), command.quotedMessageId(),
                quoted == null ? null : quoted.text(), attachmentIds));
        if (submitted.replay()) {
            // 幂等命中：返回既有消息的事实（messageId 以库中行为准，而非本次新生成的 id）。
            Log.i(TAG, "[Conversation] TEXT 幂等命中 conv=" + command.conversationId());
            ConversationStore.MessageRow existing =
                    store.findMessageByIdempotencyKey(idempotencyKey);
            String existingMessageId = existing == null ? userMessageId : existing.messageId();
            return new TextAccepted(existingMessageId,
                    existing == null ? conversationTaskId : existing.conversationTaskId(),
                    submitted.sequenceNo(), true);
        }
        // 引用快照已作为 UserSubmission 一部分在受理事务内写入；绝不在消息提交后另开
        // 一次写入，否则任意存储失败都会让“已接受”消息与引用事实发生半写分裂。
        // 用户消息先 upsert（客户端首次看到该行），再发状态事件
        ConversationStore.MessageRow persistedUserRow = store.findMessage(userMessageId);
        if (persistedUserRow != null) {
            notifyUpsert(persistedUserRow);
        }
        notifyStatus(command.conversationId(), userMessageId,
                PersistedMessageStatus.ACCEPTED.wire(), 0);

        // 3) keyed lane 预约；拒绝即 OVERLOADED 终态（消息保留、不悬挂、不谎报）。
        CancellationToken token = new CancellationToken();
        activeTokens.put(conversationTaskId, token);
        // 运行阶段（I3）：受理事务已原子持久化 → 发布 QUEUED 并绑定 Engine 事件映射。
        // 在 lane 预约前发布：订阅者先看到“等待执行”，再看到 RUNNING 消息事件。
        if (handoffContexts != null) {
            var owner = store.findConversation(command.conversationId());
            if (owner != null) handoffContexts.bind(new com.matrix.agent.handoff.HandoffContextRegistry.Binding(
                    runtimeRequestId, command.conversationId(), conversationTaskId, userMessageId,
                    submitted.sequenceNo(), owner.ownerUserId(), owner.vehicleZone()));
        }
        beginStageTracking(command.conversationId(), conversationTaskId, runtimeRequestId);
        // 必须先回执再预约 lane：极快的本地执行也不能在 VoiceBridge 建立 token→task 映射前
        // 回来，从而丢失唯一一次 TTS 回注。
        notifyAccepted(command.acceptedListener(), new TextAccepted(userMessageId,
                conversationTaskId, submitted.sequenceNo(), false));
        try {
            dispatcher.execute(command.conversationId(), ()
                    -> runPreparedTask(prepared, userMessageId, token));
        } catch (KeyedSerialDispatcher.RejectedExecutionDispatcherException rejected) {
            Log.w(TAG, "[Conversation] 派发拒绝 conv=" + command.conversationId()
                    + " cause=" + rejected.getMessage());
            activeTokens.remove(conversationTaskId);
            endStageTracking(command.conversationId(), conversationTaskId, runtimeRequestId);
            convergeTerminal(command.conversationId(), userMessageId, conversationTaskId,
                    PersistedMessageStatus.FAILED, MatrixErrorCode.OVERLOADED,
                    AssistantReply.synthesized("系统繁忙，任务未执行，请稍后重试。"));
        }
        return new TextAccepted(userMessageId, conversationTaskId, submitted.sequenceNo(),
                false);
    }

    /** 输入来源元数据。文字入口使用 TEXT/TOUCH；语音桥保留 PTT/WAKE 与 ASR 事实。 */
    public record InputMetadata(ConversationInputChannel channel, InputSource inputSource,
            String idempotencyKey, Float asrConfidence, boolean confidenceAvailable) {
        public InputMetadata {
            Objects.requireNonNull(channel, "channel");
            Objects.requireNonNull(inputSource, "inputSource");
            if (idempotencyKey == null || idempotencyKey.isBlank()) {
                throw new IllegalArgumentException("idempotencyKey 不能为空");
            }
        }
        public static InputMetadata text(String conversationId, String clientOperationId) {
            return new InputMetadata(ConversationInputChannel.TEXT, InputSource.TOUCH,
                    ConversationIds.textIdempotencyKey(conversationId, clientOperationId),
                    null, true);
        }
    }

    @FunctionalInterface
    public interface AcceptedListener {
        void onAccepted(TextAccepted accepted);
    }

    public record TextCommand(String conversationId, String text, String languageTag,
            String clientOperationId, Actor actor, String agentSessionId,
            String arbitrationKey, InputMetadata metadata, AcceptedListener acceptedListener,
            String quotedMessageId, String submittedDraftInstanceId,
            List<String> contextAttachmentIds) {
        /** 保持现有 Binder/测试调用的源码兼容。 */
        public TextCommand(String conversationId, String text, String languageTag,
                String clientOperationId, Actor actor, String agentSessionId,
                String arbitrationKey) {
            this(conversationId, text, languageTag, clientOperationId, actor, agentSessionId,
                    arbitrationKey, null, null, null, null, null);
        }

        public TextCommand(String conversationId, String text, String languageTag,
                String clientOperationId, Actor actor, String agentSessionId,
                String arbitrationKey, InputMetadata metadata, AcceptedListener acceptedListener) {
            this(conversationId, text, languageTag, clientOperationId, actor, agentSessionId,
                    arbitrationKey, metadata, acceptedListener, null, null, null);
        }

        /** Compatibility overload for callers that supply quote metadata but no draft snapshot. */
        public TextCommand(String conversationId, String text, String languageTag,
                String clientOperationId, Actor actor, String agentSessionId,
                String arbitrationKey, InputMetadata metadata, AcceptedListener acceptedListener,
                String quotedMessageId) {
            this(conversationId, text, languageTag, clientOperationId, actor, agentSessionId,
                    arbitrationKey, metadata, acceptedListener, quotedMessageId, null, null);
        }

        /** Draft-aware overload without attachments（stub/桥的常用形态）。 */
        public TextCommand(String conversationId, String text, String languageTag,
                String clientOperationId, Actor actor, String agentSessionId,
                String arbitrationKey, InputMetadata metadata, AcceptedListener acceptedListener,
                String quotedMessageId, String submittedDraftInstanceId) {
            this(conversationId, text, languageTag, clientOperationId, actor, agentSessionId,
                    arbitrationKey, metadata, acceptedListener, quotedMessageId,
                    submittedDraftInstanceId, null);
        }
    }

    /** 受理结果：messageId/sequence 是持久化事实；replay=true 表示幂等命中。 */
    public record TextAccepted(String userMessageId, String conversationTaskId,
            long sequenceNo, boolean replay) { }

    // ---------------------------------------------------------------- 统一提交（输入交互增强 §3.2）

    /**
     * 统一提交入口：在 Host 侧原子判定“并入运行中宿主任务（steer）”还是“建立新主轮次”，
     * Launcher 不预读状态自行决定。steer 分支的线性化由 appendSteerMessage 事务保证
     * （宿主终态写互斥）；判定不到可接收宿主时自然落到主轮次（排队语义仍然正确）。
     *
     * <p>幂等：先按 text:/steer: 两个键查既有行——Binder 重试无论首次落在哪个分支都
     * 返回既有事实，不会出现“首次主轮次、重试变 steer”的重复提交。文本超过追加限额
     * （512）时 steer 不可行，直接走主轮次。acceptedListener 在两个分支都会同步回执
     * （steer 分支回执宿主任务 id），VoiceBridge 的 token 映射因此对两分支同时成立。</p>
     */
    public UnifiedTextOutcome submitTextOrAppend(TextCommand command) {
        Objects.requireNonNull(command, "command");
        ConversationIds.requireLowerUuid(command.conversationId(), "conversationId");
        ConversationIds.requireLowerUuid(command.clientOperationId(), "clientOperationId");
        List<String> attachmentIds = normalizedAttachmentIds(command);
        String normalized = normalizeSubmissionText(command.text(), TEXT_MAX_CHARS,
                !attachmentIds.isEmpty());

        // 幂等前置（§3.2）：Binder/桥重试无论首次落在哪个分支都返回既有事实——
        // 先查命令自带的幂等键（PTT/WAKE metadata），再查 text:/steer: 派生键。
        if (command.metadata() != null) {
            MessageRow existing = store.findMessageByIdempotencyKey(
                    command.metadata().idempotencyKey());
            if (existing != null) {
                return replayOf(existing);
            }
        }
        MessageRow primaryExisting = store.findMessageByIdempotencyKey(
                ConversationIds.textIdempotencyKey(command.conversationId(),
                        command.clientOperationId()));
        if (primaryExisting != null) {
            return replayOf(primaryExisting);
        }
        MessageRow steerExisting = store.findMessageByIdempotencyKey(
                ConversationIds.steerIdempotencyKey(command.conversationId(),
                        command.clientOperationId()));
        if (steerExisting != null) {
            return replayOf(steerExisting);
        }

        if (normalized.length() <= APPEND_MAX_CHARS) {
            String steerKey = command.metadata() == null
                    ? ConversationIds.steerIdempotencyKey(command.conversationId(),
                            command.clientOperationId())
                    : command.metadata().idempotencyKey();
            SteerAccepted steer = appendSteerWithKey(command, normalized, steerKey,
                    attachmentIds);
            if (steer != null) {
                notifyAccepted(command.acceptedListener(),
                        new TextAccepted(steer.steerMessageId(), steer.hostConversationTaskId(),
                                steer.sequenceNo(), steer.replay()));
                return new UnifiedTextOutcome(steer.offered()
                        ? com.matrix.agent.api.conversation.ConversationSubmission.OUTCOME_STEER_ACCEPTED
                        : com.matrix.agent.api.conversation.ConversationSubmission
                                .OUTCOME_STEER_DELIVERY_FAILED,
                        steer.steerMessageId(), steer.hostConversationTaskId(), steer.sequenceNo(),
                        steer.replay());
            }
        }
        TextAccepted accepted = submitText(command);
        return new UnifiedTextOutcome(
                com.matrix.agent.api.conversation.ConversationSubmission.OUTCOME_PRIMARY_ACCEPTED,
                accepted.userMessageId(), accepted.conversationTaskId(),
                accepted.sequenceNo(), accepted.replay());
    }

    /** 既有行的统一回放：inputKind 区分分支，steer 行反查宿主任务。 */
    private UnifiedTextOutcome replayOf(MessageRow existing) {
        boolean steer = existing.inputKindWire() == MessageRow.INPUT_STEER_WIRE;
        int outcome = !steer
                ? com.matrix.agent.api.conversation.ConversationSubmission.OUTCOME_PRIMARY_ACCEPTED
                : existing.steerDeliveryWire()
                        == ConversationMessage.STEER_DELIVERY_FAILED
                        ? com.matrix.agent.api.conversation.ConversationSubmission
                                .OUTCOME_STEER_DELIVERY_FAILED
                        : com.matrix.agent.api.conversation.ConversationSubmission
                                .OUTCOME_STEER_ACCEPTED;
        return new UnifiedTextOutcome(outcome,
                existing.messageId(),
                steer ? hostTaskOf(existing) : existing.conversationTaskId(),
                existing.sequenceNo(), true);
    }

    /** steer 行没有独立 task link；其宿主任务经 steerHostUserMessageId 反查（可空）。 */
    private String hostTaskOf(MessageRow steerRow) {
        if (steerRow.steerHostUserMessageId() == null) return null;
        MessageRow host = store.findMessage(steerRow.steerHostUserMessageId());
        return host == null ? null : host.conversationTaskId();
    }

    /** 统一提交结果；outcome 取 SDK ConversationSubmission.OUTCOME_*。 */
    public record UnifiedTextOutcome(int outcome, String userMessageId,
            String conversationTaskId, long sequenceNo, boolean replay) { }

    // ---------------------------------------------------------------- 追加与取消

    /**
     * appendSteer（评估 v1.0 §4.3，仅 REPROMPT）：先原子落库 INPUT_STEER 附属行
     * （幂等键 steer: 前缀；事务内校验宿主运行 = 与宿主终态写互斥），再向宿主
     * Agent session 确认式投递。无运行宿主返回 null（stub 映射 INVALID_STATE）。
     * 幂等命中返回既有行（replay=true）且不重复投递——Binder 重试安全。
     */
    public SteerAccepted appendSteer(String conversationId, String text,
            String clientOperationId) {
        ConversationIds.requireLowerUuid(conversationId, "conversationId");
        ConversationIds.requireLowerUuid(clientOperationId, "clientOperationId");
        return appendSteerWithKey(new TextCommand(conversationId, text, null,
                        clientOperationId, Actor.DRIVER, sessionForConversation.apply(conversationId),
                        "legacy", null, null), text,
                ConversationIds.steerIdempotencyKey(conversationId, clientOperationId), List.of());
    }

    /** 显式幂等键变体（统一提交路径：PTT/WAKE metadata 键直达，重试命中同一行）。 */
    SteerAccepted appendSteerWithKey(TextCommand command, String text, String idempotencyKey,
            List<String> attachmentIds) {
        String conversationId = command.conversationId();
        ConversationIds.requireLowerUuid(conversationId, "conversationId");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey 不能为空");
        }
        String steerText = normalizeText(text, APPEND_MAX_CHARS);
        // 附件的验证、净化投影发生在任何 steer 持久化前；Steer 只得到脱敏文本，
        // 用户消息正文仍保持原话，附件事实由同一事务冻结到 INPUT_STEER 行。
        String attachmentContext = projectAttachments(command, attachmentIds);
        String agentSteerText = attachmentContext.isEmpty() ? steerText : steerText + attachmentContext;
        String messageId = ConversationIds.newMessageId();

        // 预检只挑挂载点；真正的线性化在 appendSteerMessage 事务内（宿主终态互斥）
        String hostUserMessageId = store.findRunningUserMessageId(conversationId);
        if (hostUserMessageId == null) {
            return null;
        }
        SubmittedSteerMessage submitted = store.appendSteerMessage(new SteerSubmission(
                conversationId, messageId, ConversationMessage.CHANNEL_TEXT, steerText,
                null, hostUserMessageId, idempotencyKey, command.submittedDraftInstanceId(),
                attachmentIds));
        if (submitted.replay()) {
            Log.i(TAG, "[Conversation] STEER 幂等命中 conv=" + conversationId);
            MessageRow existing = store.findMessageByIdempotencyKey(idempotencyKey);
            return new SteerAccepted(
                    existing == null ? messageId : existing.messageId(),
                    existing == null ? hostTaskForUserMessage(hostUserMessageId)
                            : hostTaskOf(existing), submitted.sequenceNo(), true,
                    existing == null || existing.steerDeliveryWire()
                            == ConversationMessage.STEER_DELIVERY_OFFERED);
        }
        MessageRow persisted = store.findMessage(messageId);
        if (persisted != null) {
            notifyUpsert(persisted);
        }

        boolean accepted = steerSink.offerSteer(sessionForConversation.apply(conversationId),
                Steer.reprompt(agentSteerText, messageId));
        store.updateSteerDelivery(messageId, accepted, command.submittedDraftInstanceId());
        if (!accepted) {
            Log.w(TAG, "[Conversation] STEER 投递被拒绝，附属输入收敛 FAILED msg="
                    + messageId);
            MessageRow rejected = store.findMessage(messageId);
            if (rejected != null) {
                notifyUpsert(rejected);
                notifyStatus(conversationId, messageId,
                        PersistedMessageStatus.FAILED.wire(), 0);
            }
        }
        return new SteerAccepted(messageId, hostTaskForUserMessage(hostUserMessageId),
                submitted.sequenceNo(), false, accepted);
    }

    /** 附属输入受理结果；replay=true 表示幂等命中既有行。 */
    public record SteerAccepted(String steerMessageId, String hostConversationTaskId,
            long sequenceNo, boolean replay, boolean offered) { }

    private String hostTaskForUserMessage(String hostUserMessageId) {
        MessageRow host = hostUserMessageId == null ? null : store.findMessage(hostUserMessageId);
        return host == null ? null : host.conversationTaskId();
    }

    // ---------------------------------------------------------------- 用户组织（评估 v1.0 阶段 3）

    /** 收藏/备注合并 upsert（幂等）；消息不存在拒绝。 */
    public void annotateMessage(String conversationId, String messageId, String ownerUserId,
            boolean favorite, String userNote) {
        ConversationIds.requireLowerUuid(conversationId, "conversationId");
        requireMessageInConversation(conversationId, messageId);
        String safeNote = userNote == null ? null : normalizeText(userNote, NOTE_MAX_CHARS);
        if (safeNote != null && safeNote.isBlank()) {
            safeNote = null; // 全空白等价清除备注
        }
        store.upsertAnnotation(new ConversationStore.AnnotationUpsert(
                messageId, ownerUserId, favorite, safeNote));
    }

    /** 分支：在父会话某已完成消息处切出子会话；快照经同一装配器输入形态物化。 */
    public String forkFrom(String parentConversationId, long atSequenceNo, String ownerUserId) {
        ConversationIds.requireLowerUuid(parentConversationId, "parentConversationId");
        ConversationStore.ConversationRow parent =
                store.findConversation(parentConversationId);
        if (parent == null || parent.archived()) {
            throw new IllegalArgumentException(
                    "父会话不存在或已归档: " + parentConversationId);
        }
        // 切点校验：必须是本会话一条已完成消息（仅从已完成建分支）
        ConversationStore.MessageWindow window =
                store.windowAround(parentConversationId, atSequenceNo, 1);
        ConversationStore.MessageRow anchor = window.anchorExists()
                && !window.messagesAscending().isEmpty()
                && window.messagesAscending().get(0).sequenceNo() == atSequenceNo
                        ? window.messagesAscending().get(0) : null;
        if (anchor == null
                || anchor.statusWire() != ConversationDomain.PersistedMessageStatus
                        .COMPLETED.wire()) {
            throw new IllegalArgumentException(
                    "切点必须是本会话已完成消息: seq=" + atSequenceNo);
        }
        // 快照 = 切点前（含）的全部已完成回合——与 history 源同形态同过滤
        List<ConversationStore.MessageRow> completed =
                store.latestCompletedForSeed(parentConversationId, Integer.MAX_VALUE);
        List<HistoryEntry> snapshot = new ArrayList<>();
        for (ConversationStore.MessageRow row : completed) {
            if (row.sequenceNo() > atSequenceNo) {
                break;
            }
            snapshot.add(new HistoryEntry(
                    row.roleWire() == ConversationMessage.ROLE_USER, row.text()));
        }
        String childId = ConversationIds.newConversationId();
        store.recordLineage(new ConversationStore.LineageRecord(
                childId, parentConversationId, atSequenceNo, parent.title(),
                BranchSeedCodec.encode(snapshot), ownerUserId));
        Log.i(TAG, "[Conversation] 分支创建 parent=" + parentConversationId
                + " at=" + atSequenceNo + " child=" + childId
                + " snapshotEntries=" + snapshot.size());
        return childId;
    }

    /** 会话活动 touch（唤醒续接等无消息写入的交互；评估 v1.0 §4.1）。 */
    public void touchConversationActivity(String conversationId) {
        ConversationIds.requireLowerUuid(conversationId, "conversationId");
        store.touchActivity(conversationId);
    }

    /** 轨迹投影直读（DTO 附加用）；无任务/未投影返回 null。 */
    public String traceJsonOf(String conversationTaskId) {
        return store.findTraceJson(conversationTaskId);
    }

    /** 分支谱系直读（来源说明投影用）；非分支会话返回 null。 */
    public ConversationStore.LineageRow lineageOf(String childConversationId) {
        ConversationIds.requireLowerUuid(childConversationId, "childConversationId");
        return store.findLineage(childConversationId);
    }

    private void requireMessageInConversation(String conversationId, String messageId) {
        ConversationStore.MessageRow row = store.findMessage(messageId);
        if (row == null || !row.conversationId().equals(conversationId)) {
            throw new IllegalArgumentException(
                    "消息不存在或不属于该会话: " + messageId);
        }
    }

    // ---------------------------------------------------------------- 列表 / 窗口 / 重命名（评估 v1.0 §4.1-4.2）

    /** 向后翻页（升序窗口）；会话不存在/被清理统一为空窗（anchorExists=false）。 */
    public ConversationStore.MessageWindow windowAfter(String conversationId,
            long afterSequenceExclusive, int limit) {
        ConversationIds.requireLowerUuid(conversationId, "conversationId");
        if (store.findConversation(conversationId) == null) {
            return ConversationStore.MessageWindow.anchorMissing();
        }
        return store.windowAfter(conversationId, afterSequenceExclusive, limit);
    }

    /** 锚点定位窗口；不可定位统一 anchorMissing（不泄漏存在性）。 */
    public ConversationStore.MessageWindow windowAround(String conversationId,
            long anchorSequence, int limit) {
        ConversationIds.requireLowerUuid(conversationId, "conversationId");
        if (store.findConversation(conversationId) == null) {
            return ConversationStore.MessageWindow.anchorMissing();
        }
        return store.windowAround(conversationId, anchorSequence, limit);
    }

    /** 用户重命名：titleOrigin 置 USER（AUTO 永不覆盖）；不存在返回 false。 */
    public boolean renameConversation(String conversationId, String title) {
        ConversationIds.requireLowerUuid(conversationId, "conversationId");
        String safeTitle = normalizeText(title, TITLE_MAX_CHARS);
        if (safeTitle.isBlank()) {
            throw new IllegalArgumentException("title 不能为空");
        }
        return store.renameConversation(conversationId, safeTitle);
    }



    /** cancelMessage：对已提交未终态任务触发协作取消；终态由执行路径如实收敛。 */
    public boolean cancelByUserMessage(String conversationId, String userMessageId) {
        ConversationIds.requireLowerUuid(conversationId, "conversationId");
        MessageRow message = store.findMessage(userMessageId);
        if (message == null || !message.conversationId().equals(conversationId)
                || message.conversationTaskId() == null) {
            return false;
        }
        CancellationToken token = activeTokens.get(message.conversationTaskId());
        if (token == null) {
            return false; // 已终态（token 随终态移除）
        }
        token.cancel();
        return true;
    }

    // ---------------------------------------------------------------- 执行（lane 线程）

    private void runPreparedTask(ConversationTaskSubmitter.PreparedTask prepared,
            String userMessageId, CancellationToken token) {
        String conversationTaskId = prepared.conversationTaskId();
        String conversationId = prepared.conversationId();
        try {
            if (!store.markRunning(conversationTaskId)) {
                Log.w(TAG, "[Conversation] markRunning 未命中 task=" + conversationTaskId);
                return;
            }
            notifyStatus(conversationId, userMessageId,
                    PersistedMessageStatus.RUNNING.wire(), 0);

            // 历史只在真正拿到本 conversation 的 lane 后读取；同 key 上前一任务的
            // writeTerminal 已完成，因此 seed 的时间语义与连续对话一致。
            ConversationTaskSubmitter.PreparedTask executable =
                    submitter.assembleAtExecution(prepared);
            AgentOutcome outcome;
            try {
                outcome = executor.execute(executable, token);
            } catch (RuntimeException executionFailure) {
                // Repository 层已覆盖调度类异常；此处兜底保证消息终态不悬挂。
                Log.e(TAG, "[Conversation] executePrepared 异常 task=" + conversationTaskId,
                        executionFailure);
                convergeTerminal(conversationId, userMessageId, conversationTaskId,
                        PersistedMessageStatus.FAILED, MatrixErrorCode.TASK_FAILED,
                        AssistantReply.synthesized("任务执行异常，未完成。"));
                return;
            }
            // 轨迹投影（评估 v1.0 §4.3）：结构化执行事实经白名单净化后随终态落列。
            // 异常兜底路径（convergeTerminal 的其它调用点）无 outcome——traceJson 保持
            // null（空轨迹），绝不编造事实。
            java.util.List<CapabilityExecutionTrace> executionTraces =
                    CapabilityTraceProjector.project(outcome);
            AssistantReply reply = ConversationExecutionReplyProjector.replaceGenericCompletion(
                    ConversationAssistantProjector.project(outcome,
                            ConversationAssistantProjector.MAX_REPLY_CHARS), executionTraces);
            String traceJson = CapabilityTraceCodec.encode(executionTraces);
            convergeTerminal(conversationId, userMessageId, conversationTaskId,
                    toPersistedStatus(outcome), failureCodeOf(outcome), reply, traceJson);
            notifyTerminal(conversationTaskId, outcome);
        } finally {
            activeTokens.remove(conversationTaskId);
            // 终态清理（I3 §6.2 顺序契约）：convergeTerminal 已先推 message upsert，
            // 此处再清运行阶段——输入栏不会短暂显示“正在执行”而消息已“已完成”。
            endStageTracking(conversationId, conversationTaskId,
                    prepared.runtimeRequestId());
        }
    }

    private void beginStageTracking(String conversationId, String conversationTaskId,
            String runtimeRequestId) {
        if (progressBridge != null) {
            progressBridge.bind(runtimeRequestId, conversationId, conversationTaskId);
        }
        if (stageRegistry != null) {
            stageRegistry.publish(conversationId, conversationTaskId,
                    ConversationRuntimeStageRegistry.stageQueued(), "");
        }
    }

    private void endStageTracking(String conversationId, String conversationTaskId,
            String runtimeRequestId) {
        if (handoffContexts != null) handoffContexts.unbind(runtimeRequestId);
        if (stageRegistry != null) {
            stageRegistry.clear(conversationId, conversationTaskId);
        }
        if (progressBridge != null) {
            progressBridge.unbind(runtimeRequestId);
        }
    }

    /** 唯一终态写出口：事务写用户消息/关联 + assistant 行，随后发双事件。 */
    private void convergeTerminal(String conversationId, String userMessageId,
            String conversationTaskId, PersistedMessageStatus status, int failureCode,
            AssistantReply reply) {
        convergeTerminal(conversationId, userMessageId, conversationTaskId, status,
                failureCode, reply, null);
    }

    private void convergeTerminal(String conversationId, String userMessageId,
            String conversationTaskId, PersistedMessageStatus status, int failureCode,
            AssistantReply reply, String traceJson) {
        String assistantMessageId = ConversationIds.newMessageId();
        ConversationStore.TerminalOutcome terminal = store.writeTerminal(new TerminalWrite(
                conversationTaskId, status.wire(), failureCode, assistantMessageId,
                reply.text(), traceJson));
        if (!terminal.written()) {
            Log.w(TAG, "[Conversation] 终态丢弃（conversation 已清除）task="
                    + conversationTaskId);
            return;
        }
        // Persisted task truth, recorded only after the terminal write succeeds.
        handoffDiagnostics.record(com.matrix.agent.diagnostics.HandoffDiagnostics.Stage.TASK_TERMINAL,
                0, status.wire(), 0, status == PersistedMessageStatus.COMPLETED,
                android.os.SystemClock.elapsedRealtime(), -1, conversationTaskId, null);
        MessageRow assistant = store.findMessage(assistantMessageId);
        if (assistant != null) {
            notifyUpsert(assistant);
        }
        // 终态后补发用户行 upsert：订阅方拿到携带终态（与轨迹投影通道）的完整行，
        // 而不必整页重读（评估 v1.0 §4.3 状态卡的推送路径）。
        MessageRow finalUserRow = store.findMessage(userMessageId);
        if (finalUserRow != null) {
            notifyUpsert(finalUserRow);
        }
        notifyStatus(conversationId, userMessageId, status.wire(), failureCode);
        // 附属 steer 的镜像收敛事件（评审 P1）：宿主终态同事务收敛的 steer 行必须
        // 逐行补发 upsert + status——否则客户端缓存停留在 RUNNING，气泡挂“正在并入”、
        // 取消入口残留，直到重进会话。事实来自事务返回值，不回查、不推断。
        for (ConversationStore.ConvergedSteer converged : terminal.convergedSteers()) {
            MessageRow steerRow = store.findMessage(converged.messageId());
            if (steerRow != null) {
                notifyUpsert(steerRow);
            }
            notifyStatus(conversationId, converged.messageId(), converged.statusWire(),
                    converged.failureCode());
        }
        // 自动标题触发点（评估 v1.0 §4.1）：第一条到达终态的用户轮次；sink 自行异步
        // 且比较交换——多次触发至多一次写入，REJECTED/CANCELLED 由 sink 过滤。
        terminalRoundSink.onTerminalRound(conversationId, userMessageId, status.wire());
    }

    // ---------------------------------------------------------------- 映射与校验

    static PersistedMessageStatus toPersistedStatus(AgentOutcome outcome) {
        TaskState state = outcome.getFinalState();
        if (state == null) {
            return PersistedMessageStatus.FAILED;
        }
        return switch (state) {
            case SUCCEEDED, PARTIALLY_SUCCEEDED -> PersistedMessageStatus.COMPLETED;
            case CANCELLED, PREEMPTED -> PersistedMessageStatus.CANCELLED;
            case EXECUTION_UNKNOWN -> PersistedMessageStatus.EXECUTION_UNKNOWN;
            default -> PersistedMessageStatus.FAILED;
        };
    }

    static int failureCodeOf(AgentOutcome outcome) {
        return toPersistedStatus(outcome) == PersistedMessageStatus.COMPLETED
                || toPersistedStatus(outcome) == PersistedMessageStatus.CANCELLED
                ? MatrixErrorCode.SUCCESS : MatrixErrorCode.TASK_FAILED;
    }

    static int channelWire(ConversationInputChannel channel) {
        return switch (channel) {
            case TEXT -> ConversationMessage.CHANNEL_TEXT;
            case PTT -> ConversationMessage.CHANNEL_PTT;
            case WAKE -> ConversationMessage.CHANNEL_WAKE;
        };
    }

    // ---------------------------------------------------------------- 附件（I6 §9.2）

    /** 规范化提交附件集合：null/空 → 空列表；去重保序。 */
    private static List<String> normalizedAttachmentIds(TextCommand command) {
        if (command.contextAttachmentIds() == null || command.contextAttachmentIds().isEmpty()) {
            return List.of();
        }
        return command.contextAttachmentIds().stream().distinct()
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * 提交前验证 + 物化投影。空集合为 no-op（空串）；带附件但端口未装配抛
     * IllegalArgumentException（附件域降级时显式拒绝，不静默丢附件）；
     * 任一附件无效同样在所有持久化动作之前抛出（§3.2 整次拒绝，不留半写）。
     * owner/zone 从会话行推导（与草稿消费同源），Launcher 不可指定。
     */
    private String projectAttachments(TextCommand command, List<String> attachmentIds) {
        if (attachmentIds.isEmpty()) return "";
        AttachmentPort port = attachmentPort;
        if (port == null) {
            throw new IllegalArgumentException("附件能力不可用（Host 未装配附件域）");
        }
        ConversationStore.ConversationRow conversation =
                store.findConversation(command.conversationId());
        if (conversation == null) {
            throw new IllegalArgumentException("conversation 不存在: "
                    + command.conversationId());
        }
        port.validateForSubmission(conversation.ownerUserId(), conversation.vehicleZone(),
                command.conversationId(), attachmentIds);
        return port.projectContext(conversation.ownerUserId(),
                conversation.vehicleZone(), command.conversationId(), attachmentIds);
    }

    /** 附件端口只读暴露（Stub 的消息 DTO 投影用）。 */
    public AttachmentPort attachmentPort() {
        return attachmentPort;
    }

    private static String normalizeText(String text, int maxChars) {
        if (text == null) throw new IllegalArgumentException("text 不能为空");
        String trimmed = text.strip();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("text 不能为空白");
        if (trimmed.length() > maxChars) {
            throw new IllegalArgumentException("text 超过上限 " + maxChars + " 字符");
        }
        return trimmed;
    }

    /**
     * 附件本身是明确、可见的输入。用户只选附件不写说明时，生成一条透明的最小意图，
     * 既满足 task 端口的非空文本契约，也不会把附件静默丢弃或要求用户凑字数。
     */
    private static String normalizeSubmissionText(String text, int maxChars,
            boolean hasAttachments) {
        if (text == null) text = "";
        String normalized = text.strip();
        if (normalized.isEmpty() && hasAttachments) {
            return "请根据我选择的附件提供帮助。";
        }
        return normalizeText(normalized, maxChars);
    }

    private static String normalizeLanguage(String languageTag) {
        return (languageTag == null || languageTag.isBlank()) ? "zh-CN" : languageTag.strip();
    }

    private void notifyUpsert(MessageRow row) {
        listener.onMessageUpsert(row);
    }

    private void notifyStatus(String conversationId, String messageId, int statusWire,
            int failureCode) {
        listener.onMessageStatusChanged(conversationId, messageId, statusWire, failureCode);
    }

    private void notifyAccepted(AcceptedListener acceptedListener, TextAccepted accepted) {
        if (acceptedListener == null) return;
        try {
            acceptedListener.onAccepted(accepted);
        } catch (RuntimeException listenerFailure) {
            Log.w(TAG, "[Conversation] accepted listener failed", listenerFailure);
        }
    }

    private void notifyTerminal(String conversationTaskId, AgentOutcome outcome) {
        for (TerminalListener terminalListener : terminalListeners) {
            try {
                terminalListener.onTerminal(conversationTaskId, outcome);
            } catch (RuntimeException listenerFailure) {
                Log.w(TAG, "[Conversation] terminal listener failed", listenerFailure);
            }
        }
    }
}
