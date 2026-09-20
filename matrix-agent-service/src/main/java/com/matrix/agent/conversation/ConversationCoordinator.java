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
import com.matrix.agent.identity.CancellationToken;
import com.matrix.agent.identity.InputSource;
import com.matrix.agent.platform.KeyedSerialDispatcher;
import com.matrix.agent.task.AgentOutcome;
import com.matrix.agent.task.TaskState;
import com.matrix.agent.task.conversation.AssistantReply;
import com.matrix.agent.task.conversation.ConversationAssistantProjector;
import com.matrix.agent.task.conversation.ConversationTaskSubmitter;
import com.matrix.agent.task.steer.Steer;

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

    private static final String TAG = "MatrixAgent";
    /** Binder 正文上限与 AgentRequest.TEXT_MAX 对齐；Host 侧再校验，不信任客户端。 */
    public static final int TEXT_MAX_CHARS = 4096;
    /** REPROMPT 追加文本沿用既有 Steer 上限语义。 */
    public static final int APPEND_MAX_CHARS = 512;

    /** 标题上限（评估 v1.0 §4.1；与 Operit 40 字符 sanitize 对齐的车机展示预算）。 */
    public static final int TITLE_MAX_CHARS = 40;
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

    /**
     * 终态轮次通知（评估 v1.0 §4.1 自动标题触发）。lane 线程回调，实现方自行异步；
     * 默认 no-op（未装配即不生成标题，不影响任何执行路径）。
     */
    public interface TerminalRoundSink {
        void onTerminalRound(String conversationId, String userMessageId, int statusWire);
    }
    private final SteerSink steerSink;
    private volatile TerminalRoundSink terminalRoundSink = (conv, msg, status) -> { };
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

    public void setListener(Listener value) {
        this.listener = (value == null) ? new Listener() { } : value;
    }

    /** 自动标题等终态观察者装配（评估 v1.0 §4.1）；未装配为 no-op。 */
    public void setTerminalRoundSink(TerminalRoundSink sink) {
        this.terminalRoundSink = sink == null ? (conv, msg, status) -> { } : sink;
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
        String text = normalizeText(command.text(), TEXT_MAX_CHARS);
        String languageTag = normalizeLanguage(command.languageTag());

        String conversationTaskId = ConversationIds.newConversationTaskId();
        String runtimeRequestId = ConversationIds.newRuntimeRequestId();
        String userMessageId = ConversationIds.newMessageId();
        InputMetadata metadata = command.metadata() == null
                ? InputMetadata.text(command.conversationId(), command.clientOperationId())
                : command.metadata();
        String idempotencyKey = metadata.idempotencyKey();

        // 1) 纯准备：只固化分类快照。历史种子必须在 keyed lane 真正出队时装配，
        // 才能看到同一 conversation 的前一条终态，且不会把排队时间烧进 Agent budget。
        ConversationTaskSubmitter.PreparedTask prepared = submitter.prepare(
                new ConversationTaskSubmitter.SubmitInput(
                        conversationTaskId, runtimeRequestId, command.conversationId(), text,
                        command.actor(), command.agentSessionId(), command.arbitrationKey(),
                        metadata.inputSource(), languageTag, metadata.asrConfidence(),
                        metadata.confidenceAvailable()));

        // 2) 原子事务：幂等判重 + sequence + 用户消息(ACCEPTED) + task link。
        SubmittedUserMessage submitted = store.submitUserMessage(new UserSubmission(
                command.conversationId(), userMessageId,
                channelWire(metadata.channel()), text, languageTag,
                conversationTaskId, runtimeRequestId,
                prepared.classification().readOnlyHint(), idempotencyKey));
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
            String arbitrationKey, InputMetadata metadata, AcceptedListener acceptedListener) {
        /** 保持现有 Binder/测试调用的源码兼容。 */
        public TextCommand(String conversationId, String text, String languageTag,
                String clientOperationId, Actor actor, String agentSessionId,
                String arbitrationKey) {
            this(conversationId, text, languageTag, clientOperationId, actor, agentSessionId,
                    arbitrationKey, null, null);
        }
    }

    /** 受理结果：messageId/sequence 是持久化事实；replay=true 表示幂等命中。 */
    public record TextAccepted(String userMessageId, String conversationTaskId,
            long sequenceNo, boolean replay) { }

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
        String steerText = normalizeText(text, APPEND_MAX_CHARS);
        String messageId = ConversationIds.newMessageId();
        String idempotencyKey =
                ConversationIds.steerIdempotencyKey(conversationId, clientOperationId);

        // 预检只挑挂载点；真正的线性化在 appendSteerMessage 事务内（宿主终态互斥）
        String hostUserMessageId = store.findRunningUserMessageId(conversationId);
        if (hostUserMessageId == null) {
            return null;
        }
        SubmittedSteerMessage submitted = store.appendSteerMessage(new SteerSubmission(
                conversationId, messageId, ConversationMessage.CHANNEL_TEXT, steerText,
                null, hostUserMessageId, idempotencyKey));
        if (submitted.replay()) {
            Log.i(TAG, "[Conversation] STEER 幂等命中 conv=" + conversationId);
            MessageRow existing = store.findMessageByIdempotencyKey(idempotencyKey);
            return new SteerAccepted(
                    existing == null ? messageId : existing.messageId(),
                    submitted.sequenceNo(), true);
        }
        MessageRow persisted = store.findMessage(messageId);
        if (persisted != null) {
            notifyUpsert(persisted);
        }

        boolean accepted = steerSink.offerSteer(sessionForConversation.apply(conversationId),
                Steer.reprompt(steerText, messageId));
        store.updateSteerDelivery(messageId, accepted);
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
        return new SteerAccepted(messageId, submitted.sequenceNo(), false);
    }

    /** 附属输入受理结果；replay=true 表示幂等命中既有行。 */
    public record SteerAccepted(String steerMessageId, long sequenceNo, boolean replay) { }

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
            AssistantReply reply = ConversationAssistantProjector.project(outcome,
                    ConversationAssistantProjector.MAX_REPLY_CHARS);
            convergeTerminal(conversationId, userMessageId, conversationTaskId,
                    toPersistedStatus(outcome), failureCodeOf(outcome), reply);
            notifyTerminal(conversationTaskId, outcome);
        } finally {
            activeTokens.remove(conversationTaskId);
        }
    }

    /** 唯一终态写出口：事务写用户消息/关联 + assistant 行，随后发双事件。 */
    private void convergeTerminal(String conversationId, String userMessageId,
            String conversationTaskId, PersistedMessageStatus status, int failureCode,
            AssistantReply reply) {
        String assistantMessageId = ConversationIds.newMessageId();
        if (!store.writeTerminal(new TerminalWrite(conversationTaskId, status.wire(),
                failureCode, assistantMessageId, reply.text()))) {
            Log.w(TAG, "[Conversation] 终态丢弃（conversation 已清除）task="
                    + conversationTaskId);
            return;
        }
        MessageRow assistant = store.findMessage(assistantMessageId);
        if (assistant != null) {
            notifyUpsert(assistant);
        }
        notifyStatus(conversationId, userMessageId, status.wire(), failureCode);
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

    private static String normalizeText(String text, int maxChars) {
        if (text == null) throw new IllegalArgumentException("text 不能为空");
        String trimmed = text.strip();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("text 不能为空白");
        if (trimmed.length() > maxChars) {
            throw new IllegalArgumentException("text 超过上限 " + maxChars + " 字符");
        }
        return trimmed;
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
