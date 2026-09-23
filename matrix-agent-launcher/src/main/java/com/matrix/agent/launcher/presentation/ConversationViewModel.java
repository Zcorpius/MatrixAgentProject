package com.matrix.agent.launcher.presentation;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.matrix.agent.api.common.MatrixErrorCode;
import com.matrix.agent.api.conversation.ConversationDraft;
import com.matrix.agent.api.conversation.ConversationInfo;
import com.matrix.agent.api.conversation.ConversationMessage;
import com.matrix.agent.api.conversation.ConversationRuntimeStage;
import com.matrix.agent.api.conversation.ConversationSubmission;
import com.matrix.agent.api.debug.DebugTraceWireEvent;
import com.matrix.agent.api.voice.VoiceServiceStatus;
import com.matrix.agent.launcher.BuildConfig;
import com.matrix.agent.launcher.data.ConversationRepository.ConversationListener;
import com.matrix.agent.launcher.data.ConversationRepository;
import com.matrix.agent.launcher.data.LauncherHostGateway;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 对话页状态机（阶段 A：文字通道）。事件按 messageId 合并、按 sequence 排序渲染；
 * EXECUTION_UNKNOWN 忠实展示为“执行结果未知”，绝不渲染成取消或失败（§8.3）。
 */
public final class ConversationViewModel extends ViewModel {

    private static final String TAG = "MatrixAgent";

    /** 可渲染消息（Host 投影的不可变快照；v5 元数据随行）。 */
    public record UiMessage(String messageId, long sequence, int role, int status, int channel,
            String text, int failureCode, int inputKind, String steerHostUserMessageId,
            int steerDeliveryState, String conversationTaskId,
            List<com.matrix.agent.api.conversation.CapabilityTraceEntry> executionTraces,
            List<DebugTraceWireEvent> debugTraces) {

        /** 兼容构造（v5 元数据缺省）。 */
        public UiMessage(String messageId, long sequence, int role, int status, int channel,
                String text, int failureCode) {
            this(messageId, sequence, role, status, channel, text, failureCode,
                    ConversationMessage.INPUT_PRIMARY, null,
                    ConversationMessage.STEER_DELIVERY_PENDING, null, List.of(), List.of());
        }

        /** 状态迁移拷贝（保留全部元数据）。 */
        public UiMessage withStatus(int newStatus, int newFailureCode) {
            return new UiMessage(messageId, sequence, role, newStatus, channel, text,
                    newFailureCode, inputKind, steerHostUserMessageId, steerDeliveryState,
                    conversationTaskId, executionTraces, debugTraces);
        }

        public UiMessage withDebugTraces(List<DebugTraceWireEvent> traces) {
            return new UiMessage(messageId, sequence, role, status, channel, text, failureCode,
                    inputKind, steerHostUserMessageId, steerDeliveryState, conversationTaskId,
                    executionTraces, traces == null ? List.of() : List.copyOf(traces));
        }
    }

    /**
     * PTT 阶段（输入交互增强 §5.2 可视化）：Fragment 纯渲染的唯一事实源。
     * 转移只经 {@link #nextPhase}，手势、语音回调与消息回流各自映射为事件。
     */
    public enum PttPhase {
        /** 空闲：按钮默认“按住说话”，状态条隐藏。 */
        IDLE,
        /** 按下：绑定/Binder/模型装配中，Host 尚未确认采音。 */
        ARMING,
        /** Host capture-started（SESSION_LISTENING）：partial 持续上屏。 */
        LISTENING,
        /** 已松开：flush→final→统一提交中；用户消息落库或会话终结后回 IDLE。 */
        PROCESSING
    }

    /** 阶段机输入（与 {@link #nextPhase} 同为包私有，供单测覆盖全部组合）。 */
    enum PttEvent {
        /** Host 确认采音（SESSION_LISTENING），或首条转写到达（次序不保证，两者互为证据）。 */
        CAPTURE_STARTED,
        /** 会话进入 THINKING/SPEAKING：final 已产出，进入提交收敛。 */
        FINAL_UNDERWAY,
        /** 用户松开且会话已建立：冲刷 final。 */
        RELEASED,
        /** 会话终结、本轮取消或复位。 */
        TERMINATED
    }

    /** 纯转移函数（单测覆盖全部组合）：非法或无变化的输入原样返回 current。 */
    static PttPhase nextPhase(PttPhase current, PttEvent event) {
        return switch (event) {
            case CAPTURE_STARTED -> current == PttPhase.ARMING ? PttPhase.LISTENING : current;
            case FINAL_UNDERWAY -> current == PttPhase.LISTENING ? PttPhase.PROCESSING : current;
            case RELEASED -> current == PttPhase.ARMING || current == PttPhase.LISTENING
                    ? PttPhase.PROCESSING : current;
            case TERMINATED -> PttPhase.IDLE;
        };
    }

    public static final class State {
        public final String conversationId;
        public final List<UiMessage> messages;
        public final boolean sending;
        public final boolean loadingHistory;
        public final boolean hasMoreHistory;
        public final String transientError;
        public final boolean hasRunningTask;
        public final PttPhase pttPhase;
        public final String liveTranscript;
        /** 摘要续聊标记（读时重算；评估 v1.0 §4.6）。 */
        public final boolean summaryActive;
        /** 当前会话标题（自动/用户来源由 Host 维护）。 */
        public final String conversationTitle;
        /** Host 驱动的运行阶段（I3）；null = 无活跃任务阶段。 */
        public final ConversationRuntimeStage runtimeStage;
        /** 草稿附件 chips（I6）：READY 才可提交，FAILED 显示原因。 */
        public final List<com.matrix.agent.api.conversation.ConversationAttachment>
                draftAttachments;
        /** 模型胶囊（I5）：当前 Host 运行时投影；null = 未配置。 */
        public final com.matrix.agent.api.model.ModelRuntimeStatus modelRuntime;

        State(String conversationId, List<UiMessage> messages, boolean sending,
                boolean loadingHistory, boolean hasMoreHistory, String transientError,
                boolean hasRunningTask, PttPhase pttPhase, String liveTranscript,
                boolean summaryActive, String conversationTitle,
                ConversationRuntimeStage runtimeStage,
                List<com.matrix.agent.api.conversation.ConversationAttachment>
                        draftAttachments,
                com.matrix.agent.api.model.ModelRuntimeStatus modelRuntime) {
            this.conversationId = conversationId;
            this.messages = messages;
            this.sending = sending;
            this.loadingHistory = loadingHistory;
            this.hasMoreHistory = hasMoreHistory;
            this.transientError = transientError;
            this.hasRunningTask = hasRunningTask;
            this.pttPhase = pttPhase;
            this.liveTranscript = liveTranscript;
            this.summaryActive = summaryActive;
            this.conversationTitle = conversationTitle;
            this.runtimeStage = runtimeStage;
            this.draftAttachments = draftAttachments;
            this.modelRuntime = modelRuntime;
        }
    }

    private static final int MAX_RENDERED = 200;
    /** 草稿 debounce（I4 §7.2）：输入停止 350ms 后保存；失焦/切换/提交立即 flush。 */
    private static final long DRAFT_DEBOUNCE_MS = 350L;

    private final ConversationRepository repository;
    private final MutableLiveData<State> state = new MutableLiveData<>(
            new State(null, List.of(), false, false, false, null, false, PttPhase.IDLE, null,
                    false, null, null, List.of(), null));
    /** 渲染缓冲：sequence 升序（TreeMap），事件按 messageId 等值合并。 */
    private final TreeMap<Long, UiMessage> bySequence = new TreeMap<>();
    /** 草稿恢复事件（一次性下发到 EditText；不在 State 里避免每键重绑输入框）。 */
    private final MutableLiveData<DraftRestore> draftRestores = new MutableLiveData<>();

    private String conversationId;
    private AutoCloseable subscription;
    private AutoCloseable debugSubscription;
    private boolean sending;
    private boolean loadingHistory;
    // ---- PTT 阶段机（§5.2 可视化）----
    private PttPhase pttPhase = PttPhase.IDLE;
    /** 每次按压递增；语音回调闭包捕获本代次，旧按压的迟到回调不得污染新一轮。 */
    private volatile int pttAttempt;
    /** 用户在启动回调返回前松开/取消：记录意图，拿到 session 后立即执行（§5.2）。 */
    private boolean stopRequestedWhileStarting;
    private boolean cancelRequestedWhileStarting;
    private String activeVoiceSessionId;
    private String liveTranscript;
    private boolean hasMoreHistory;
    private boolean hasRunningTask;
    /** Host 已连上但 ConversationGraph 仍在恢复对账时的短暂 gate；只做有界重试。 */
    private int bootstrapRetryCount;
    private boolean summaryActive;
    private String conversationTitle;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    // ---- 草稿状态机（I4）：instance/revision 单调，lane 串行保存 ----
    private String draftInstanceId = java.util.UUID.randomUUID().toString();
    private long draftRevision;
    private boolean draftLoaded;
    private String draftText = "";
    private int draftSelectionStart;
    private int draftSelectionEnd;
    private boolean draftDirty;
    private int draftSaveGeneration;
    /** 已送往 Host、尚未收到 receipt 的不可变草稿快照；输入在这段极短窗口冻结。 */
    private PendingDraftSubmission pendingDraftSubmission;
    // ---- 运行阶段（I3） ----
    private ConversationRuntimeStage runtimeStage;
    // ---- 附件（I6）----
    /** 本会话 READY 草稿附件的稳定列表（chips 渲染 + 提交携带；同 id 不重建）。 */
    private List<com.matrix.agent.api.conversation.ConversationAttachment> submittedAttachments =
            List.of();
    private boolean attachmentRefreshInFlight;
    private int attachmentStagingPolls;
    private static final int MAX_ATTACHMENT_STAGING_POLLS = 120;
    /** host userMessageId → 已持久化/实时调试事件；只在 debug build 有内容。 */
    private final Map<String, List<DebugTraceWireEvent>> debugByHostMessage =
            new LinkedHashMap<>();
    private final Set<String> debugHistoryRequested = new HashSet<>();

    // ---- 模型胶囊（I5）----
    /** 最近一次 Host 运行时状态；null = 未就绪（胶囊显示“未配置模型”）。 */
    private com.matrix.agent.api.model.ModelRuntimeStatus modelRuntime;
    /** 回执丢失不是业务拒绝：保留同一 operationId 重放，避免用户二次点击重复执行。 */
    private PendingDraftSubmission uncertainSubmission;

    public ConversationViewModel(ConversationRepository repository) {
        this.repository = repository;
    }

    public LiveData<State> state() {
        return state;
    }

    /** 草稿恢复事件（一次性应用到输入框；不进 State，避免每键重绑 EditText）。 */
    public LiveData<DraftRestore> draftRestores() {
        return draftRestores;
    }

    public record DraftRestore(String text, int selectionStart, int selectionEnd) { }

    public boolean isHostConnected() {
        return repository.isHostConnected();
    }

    /** 页面进入：复用最近未归档线程（否则新建）并订阅。 */
    public void start() {
        if (conversationId != null) {
            subscribeCurrent();
            loadHistory();
            loadDraft();
            refreshDraftAttachments();
            return;
        }
        repository.listConversations(result -> {
            if (!result.isSuccess()) {
                retryBootstrap();
                return;
            }
            List<ConversationInfo> conversations =
                    (result.isSuccess() && result.value != null) ? result.value : List.of();
            if (!conversations.isEmpty()) {
                conversationId = conversations.get(0).conversationId;
                subscribeCurrent();
                loadHistory();
                loadDraft();
                refreshDraftAttachments();
                publish(null);
            } else {
                repository.createConversation(null, created -> {
                    if (created.isSuccess() && created.value != null) {
                        conversationId = created.value.conversationId;
                        subscribeCurrent();
                        loadDraft();
                        refreshDraftAttachments();
                        publish(null);
                    } else {
                        retryBootstrap();
                    }
                });
            }
        });
    }

    private void retryBootstrap() {
        if (conversationId != null || bootstrapRetryCount >= 4) {
            publish("无法创建对话（Host 未就绪或未通告对话域）");
            return;
        }
        bootstrapRetryCount++;
        // Host 的 CONNECTED 只代表 SDK Binder 成功；ConversationGraph 还需完成一次 DB 恢复。
        // 不把这个短窗口暴露成永久失败，也不做无限重试。
        mainHandler.postDelayed(this::start, 300L);
    }

    /** 发送文字；本地立即进入 sending，Host 受理后由订阅事件回填消息行。 */
    public void send(@NonNull String rawText) {
        String text = rawText.strip();
        if (text.isEmpty() || conversationId == null || sending) {
            Log.w(TAG, "[ConversationUi] send ignored empty=" + text.isEmpty()
                    + " hasConversation=" + (conversationId != null) + " sending=" + sending);
            return;
        }
        Log.i(TAG, "[ConversationUi] send requested chars=" + text.length()
                + " conversation=" + conversationId);
        sending = true;
        publish(null);
        repository.sendText(conversationId, text, UUID.randomUUID().toString(), result -> {
            sending = false;
            if (!result.isSuccess() || result.value == null) {
                Log.w(TAG, "[ConversationUi] send transport failed");
                publish("发送失败：Host 未连接");
                return;
            }
            if (result.value.code != MatrixErrorCode.SUCCESS) {
                Log.w(TAG, "[ConversationUi] send rejected code=" + result.value.code);
                publish("发送被拒绝（错误码：" + result.value.code + "）");
                return;
            }
            Log.i(TAG, "[ConversationUi] send accepted by Host");
            // 受理成功：ACCEPTED 行由订阅事件回填；此处仅解除 sending。
            publish(null);
        });
    }

    /** 追加到当前运行任务（REPROMPT）；UI 仅在有运行任务时展示该入口。 */
    public void appendToRunning(@NonNull String rawText) {
        String text = rawText.strip();
        if (text.isEmpty() || conversationId == null || !hasRunningTask) {
            return;
        }
        repository.appendMessage(conversationId, text, UUID.randomUUID().toString(),
                result -> {
                    if (!result.isSuccess() || result.value == null
                            || result.value.code != MatrixErrorCode.SUCCESS) {
                        publish("追加未被接受（任务可能已结束）");
                    } else {
                        publish(null);
                    }
                });
    }

    /** 取消最新未终态用户消息对应的任务。 */
    public void cancelLatest() {
        if (conversationId == null) return;
        UiMessage latestUser = latestCancellablePrimary(bySequence.values());
        if (latestUser == null) return;
        String target = latestUser.messageId();
        repository.cancelMessage(conversationId, target, UUID.randomUUID().toString(),
                result -> {
                    if (!result.isSuccess() || result.value == null
                            || result.value.code != MatrixErrorCode.SUCCESS) {
                        publish("取消请求未被接受（任务可能已结束）");
                    } else {
                        publish(null);
                    }
                });
    }

    /** Pure selection rule shared by UI tests: steer is never itself a cancellable task. */
    static UiMessage latestCancellablePrimary(Iterable<UiMessage> messages) {
        UiMessage result = null;
        for (UiMessage message : messages) {
            // steer 是并入既有执行的附属输入，没有独立 task link；把它传给
            // cancelMessage 只会得到“任务不存在”。取消动作应稳定地指向最近仍未终态的
            // 主轮次，无论其后用户追加了多少 REPROMPT。
            if (message.role() == ConversationMessage.ROLE_USER
                    && message.inputKind() == ConversationMessage.INPUT_PRIMARY
                    && !isTerminal(message.status())) {
                result = message;
            }
        }
        return result;
    }

    /** PTT 按下（§5.2）：ARMING → 创建一次性绑定 → 启动语音会话；LISTENING 由 Host 状态回调确认。 */
    public void startRecording() {
        if (conversationId == null || pttPhase == PttPhase.ARMING
                || pttPhase == PttPhase.LISTENING || !repository.isHostConnected()) {
            return;
        }
        final int attempt = ++pttAttempt;
        pttPhase = PttPhase.ARMING;
        stopRequestedWhileStarting = false;
        cancelRequestedWhileStarting = false;
        publish(null);
        String convId = conversationId;
        String operationId = UUID.randomUUID().toString();
        repository.createVoiceBinding(convId, operationId, bindingResult -> {
            if (attempt != pttAttempt) return;
            if (!bindingResult.isSuccess() || bindingResult.value == null) {
                resetPtt("无法创建语音绑定（Host 未就绪）");
                return;
            }
            String bindingOperationId = bindingResult.value;
            repository.startVoiceSession(bindingOperationId, pttListener(attempt),
                sessionResult -> {
                    if (attempt != pttAttempt) return;
                    if (!sessionResult.isSuccess() || sessionResult.value == null) {
                        resetPtt("录音启动失败");
                        return;
                    }
                    activeVoiceSessionId = sessionResult.value.sessionId;
                    if (stopRequestedWhileStarting) {
                        stopRequestedWhileStarting = false;
                        stopRecording();
                        return;
                    }
                    if (cancelRequestedWhileStarting) {
                        cancelRequestedWhileStarting = false;
                        cancelRecording();
                        return;
                    }
                    publish(null);
                });
        });
    }

    /** PTT 松开：flush 出 final 并进入统一提交；用户消息落库（CHANNEL_PTT upsert）后回 IDLE。 */
    public void stopRecording() {
        if (pttPhase == PttPhase.IDLE || pttPhase == PttPhase.PROCESSING) {
            return;
        }
        if (activeVoiceSessionId == null) {
            // 用户很快松手时，Binder 启动回调尚未到；记录意图，拿到 session 后立即 flush，
            // 不能把这次 PTT 静默变成常驻录音。
            stopRequestedWhileStarting = true;
            return;
        }
        final int attempt = pttAttempt;
        String sessionId = activeVoiceSessionId;
        activeVoiceSessionId = null;
        transitionPtt(PttEvent.RELEASED);
        repository.finishRecording(sessionId, UUID.randomUUID().toString(),
                result -> {
                    if (attempt != pttAttempt) return;
                    if ((!result.isSuccess() || result.value == null
                            || result.value.code != MatrixErrorCode.SUCCESS)
                            && pttPhase != PttPhase.IDLE) {
                        // 会话已先行终结且成因已告知用户（如未识别到内容）时，迟到的
                        // NOT_FOUND 不再是新信息，不得覆盖那条提示，也不该误报为失败。
                        resetPtt("结束录音失败");
                    }
                });
    }

    /** PTT 取消（ACTION_CANCEL，§5.2）：丢弃 pendingFinal，本轮不产生任何消息。 */
    public void cancelRecording() {
        if (pttPhase == PttPhase.IDLE || pttPhase == PttPhase.PROCESSING) {
            return;
        }
        if (activeVoiceSessionId == null) {
            stopRequestedWhileStarting = false; // 取消意图优先于 flush 意图
            cancelRequestedWhileStarting = true;
            return;
        }
        final int attempt = pttAttempt;
        String sessionId = activeVoiceSessionId;
        resetPtt(null);
        repository.cancelRecording(sessionId, UUID.randomUUID().toString(),
                result -> {
                    if (attempt != pttAttempt) return;
                    if (!result.isSuccess() || result.value == null
                            || result.value.code != MatrixErrorCode.SUCCESS) {
                        publish("取消录音失败");
                    }
                });
    }

    /** 本轮 PTT 的语音回调（经 attempt 门卫）：SDK 在事件 Handler 线程派发，publish 以 postValue 落主线程。 */
    private ConversationRepository.VoiceInputListener pttListener(int attempt) {
        return new ConversationRepository.VoiceInputListener() {
            @Override public void onStateChanged(String sessionId, int sessionState) {
                if (attempt != pttAttempt) return;
                onPttSessionStateChanged(sessionState);
            }
            @Override public void onPartialText(String sessionId, String text) {
                if (attempt != pttAttempt) return;
                onPttTranscript(text);
            }
            @Override public void onFinalText(String sessionId, String text) {
                if (attempt != pttAttempt) return;
                onPttTranscript(text);
            }
            @Override public void onError(String sessionId, int errorCode) {
                if (attempt != pttAttempt) return;
                resetPtt("录音会话异常（错误码：" + errorCode + "）");
            }
        };
    }

    /** Host 会话状态：LISTENING 确认采音；IDLE 终结本轮；THINKING/SPEAKING = final 已产出。 */
    private void onPttSessionStateChanged(int sessionState) {
        if (sessionState == VoiceServiceStatus.SESSION_LISTENING) {
            transitionPtt(PttEvent.CAPTURE_STARTED);
        } else if (sessionState == VoiceServiceStatus.SESSION_IDLE) {
            // 会话终结而本轮始终没有产出用户消息（正常受理时消息 upsert 会先把
            // PROCESSING 收敛回 IDLE）：必须给出诚实解释，不能让状态条静默消失。
            if (pttPhase == PttPhase.PROCESSING) {
                resetPtt("未识别到有效语音内容，本轮未发送");
            } else if (pttPhase != PttPhase.IDLE) {
                resetPtt("录音会话已提前结束（未识别到语音）");
            }
        } else {
            transitionPtt(PttEvent.FINAL_UNDERWAY);
        }
    }

    /** partial/final 转写：既是上屏数据，也是采音已开始的证据（与状态回调次序不保证）。 */
    private void onPttTranscript(String text) {
        PttPhase next = nextPhase(pttPhase, PttEvent.CAPTURE_STARTED);
        if (next != PttPhase.LISTENING && next != PttPhase.PROCESSING) return;
        pttPhase = next;
        liveTranscript = text;
        publish(null);
    }

    private void transitionPtt(PttEvent event) {
        PttPhase next = nextPhase(pttPhase, event);
        if (next == pttPhase) return;
        pttPhase = next;
        publish(null);
    }

    /** 复位本轮 PTT（取消/错误/会话终结）：阶段、转写与 pending 意图一并清空。 */
    private void resetPtt(String error) {
        pttPhase = PttPhase.IDLE;
        liveTranscript = null;
        activeVoiceSessionId = null;
        stopRequestedWhileStarting = false;
        cancelRequestedWhileStarting = false;
        publish(error);
    }

    public void refresh() {
        if (conversationId != null) {
            loadHistory();
        } else {
            start();
        }
    }

    /** 向前翻一页更早历史。 */
    public void loadOlder() {
        if (conversationId == null || bySequence.isEmpty() || loadingHistory) {
            return;
        }
        loadingHistory = true;
        publish(null);
        long oldest = bySequence.firstKey();
        repository.pageMessages(conversationId, oldest, result -> {
            loadingHistory = false;
            if (!result.isSuccess() || result.value == null) {
                publish("历史加载失败：Host 未连接");
                return;
            }
            for (ConversationMessage message : result.value.messages) {
                merge(message);
            }
            hasMoreHistory = result.value.hasMore;
            publish(null);
        });
    }

    /** 用户重命名（titleOrigin→USER，AUTO 永不覆盖）。 */
    public void rename(String title) {
        if (conversationId == null || title == null || title.isBlank()) return;
        repository.renameConversation(conversationId, title.strip(), result -> {
            if (result.isSuccess() && result.value != null
                    && result.value.code == MatrixErrorCode.SUCCESS) {
                conversationTitle = title.strip();
                publish(null);
            } else {
                publish("重命名未生效");
            }
        });
    }

    /** 收藏/取消收藏。 */
    public void toggleFavorite(String messageId, boolean favorite) {
        if (conversationId == null) return;
        repository.annotateMessage(conversationId, messageId, favorite, null, result -> {
            if (!result.isSuccess() || result.value == null
                    || result.value.code != MatrixErrorCode.SUCCESS) {
                publish("收藏操作未生效");
            } else {
                publish(null);
            }
        });
    }

    /** 引用回复发送（quotedMessageId 走 Host 校验 + 快照）。 */
    public void sendQuoting(String quotedMessageId, String rawText) {
        String text = rawText == null ? "" : rawText.strip();
        if (text.isEmpty() || conversationId == null || sending) return;
        sending = true;
        publish(null);
        repository.sendQuotedText(conversationId, text, quotedMessageId, result -> {
            sending = false;
            if (!result.isSuccess() || result.value == null
                    || result.value.code != MatrixErrorCode.SUCCESS) {
                publish("引用回复发送被拒绝");
            } else {
                publish(null);
            }
        });
    }

    /** 快照式分支：在已完成消息处"从这里继续"。 */
    public void forkFromHere(long atSequenceNo,
            java.util.function.Consumer<String> onChildReady) {
        if (conversationId == null) return;
        repository.forkConversation(conversationId, atSequenceNo, result -> {
            if (result.isSuccess() && result.value != null) {
                // 切换到子会话
                switchConversation(result.value);
                if (onChildReady != null) onChildReady.accept(result.value);
            } else {
                publish("分支创建失败（切点需为已完成消息）");
            }
        });
    }

    /** 切换会话（列表点击/分支后）：flush 旧草稿 → 重置本地态 → 订阅/历史/草稿。 */
    public void switchConversation(String targetConversationId) {
        if (targetConversationId == null) return;
        flushDraft(); // lane 串行保证切换前保存先于新会话任何草稿命令
        conversationId = targetConversationId;
        bySequence.clear();
        debugByHostMessage.clear();
        debugHistoryRequested.clear();
        hasMoreHistory = false;
        conversationTitle = null;
        summaryActive = false;
        runtimeStage = null;
        draftLoaded = false;
        draftInstanceId = UUID.randomUUID().toString();
        draftRevision = 0;
        draftDirty = false;
        draftText = "";
        // 切换后不能把旧会话的 chip 或不确定重试令牌带入新会话。
        submittedAttachments = List.of();
        attachmentStagingPolls = 0;
        uncertainSubmission = null;
        subscribeCurrent();
        loadHistory();
        loadDraft();
        refreshDraftAttachments();
        refreshTitleAndMarker();
        publish(null);
    }

    /** 朗读一条助手最终回复。 */
    public void readAloud(String assistantMessageId) {
        if (conversationId == null) return;
        repository.speakAssistantMessage(conversationId, assistantMessageId, result -> {
            if (!result.isSuccess() || result.value == null) {
                publish("朗读不可用（Host 未就绪）");
            } else if (result.value.code == MatrixErrorCode.INVALID_STATE) {
                publish("语音会话进行中，暂不能朗读");
            } else if (result.value.code != MatrixErrorCode.SUCCESS) {
                publish("该消息暂不能朗读（需已完成的助手回复）");
            } else {
                publish(null);
            }
        });
    }

    /** 标题 + 摘要标记刷新（切换/进入/终态后）。 */
    public void refreshTitleAndMarker() {
        if (conversationId == null) return;
        repository.listConversations(result -> {
            if (result.isSuccess() && result.value != null) {
                for (ConversationInfo info : result.value) {
                    if (info.conversationId.equals(conversationId)) {
                        conversationTitle = info.title == null || info.title.isBlank()
                                ? "未命名对话" : info.title;
                        break;
                    }
                }
                publish(null);
            }
        });
        repository.wouldSummarizeOnNextRound(conversationId, result -> {
            if (result.isSuccess() && result.value != null) {
                summaryActive = result.value;
                publish(null);
            }
        });
    }

    public void closeSubscription() {
        AutoCloseable handle = subscription;
        subscription = null;
        if (handle != null) {
            try {
                handle.close();
            } catch (Exception ignored) {
            }
        }
        AutoCloseable debugHandle = debugSubscription;
        debugSubscription = null;
        if (debugHandle != null) {
            try {
                debugHandle.close();
            } catch (Exception ignored) {
            }
        }
    }

    @Override protected void onCleared() {
        mainHandler.removeCallbacksAndMessages(null);
        closeSubscription();
        super.onCleared();
    }

    // ---------------------------------------------------------------- 草稿（I4）与统一提交（§3.2）

    /** 会话就绪/切换后加载 Host 草稿；无草稿恢复为空输入。 */
    public void loadDraft() {
        if (conversationId == null) return;
        final String target = conversationId;
        repository.getDraft(target, result -> {
            if (!target.equals(conversationId)) return; // 已切换会话，丢弃迟到恢复
            draftLoaded = true;
            if (!result.isSuccess() || result.value == null) {
                draftInstanceId = UUID.randomUUID().toString();
                draftRevision = 0;
                draftDirty = false;
                draftText = "";
                draftSelectionStart = 0;
                draftSelectionEnd = 0;
                draftRestores.setValue(new DraftRestore("", 0, 0));
                return;
            }
            ConversationDraft row = result.value;
            draftInstanceId = row.draftInstanceId;
            draftRevision = row.revision;
            draftDirty = false;
            String text = row.text == null ? "" : row.text;
            draftText = text;
            draftSelectionStart = clampSelection(row.selectionStart, text);
            draftSelectionEnd = clampSelection(row.selectionEnd, text);
            draftRestores.setValue(new DraftRestore(text,
                    draftSelectionStart, draftSelectionEnd));
        });
    }

    /** 输入变化（内联/全屏编辑器共用）；350ms debounce 保存（§7.2）。 */
    public void onDraftChanged(String text, int selectionStart, int selectionEnd) {
        if (!draftLoaded) return;
        draftText = text == null ? "" : text;
        PendingDraftSubmission retry = uncertainSubmission;
        if (retry != null && (!retry.conversationId().equals(conversationId)
                || !retry.text().equals(draftText))) {
            // 用户已经改写过输入，之后必须生成新的操作；复用旧 id 会把新文本错误地
            // 解释成一次不确定请求的重放。
            uncertainSubmission = null;
        }
        draftSelectionStart = clampSelection(selectionStart, draftText);
        draftSelectionEnd = clampSelection(selectionEnd, draftText);
        draftDirty = true;
        final int generation = ++draftSaveGeneration;
        mainHandler.postDelayed(() -> {
            if (generation == draftSaveGeneration) flushDraft();
        }, DRAFT_DEBOUNCE_MS);
    }

    /** 立即保存（失焦/切换会话/全屏关闭/onStop/提交前调用）。 */
    public void flushDraft() {
        if (!draftLoaded || conversationId == null || !draftDirty) return;
        draftDirty = false;
        draftSaveGeneration++; // 取消在途 debounce
        final String conversation = conversationId;
        final String instance = draftInstanceId;
        final long revision = draftRevision + 1;
        final String text = draftText;
        final int selectionStart = draftSelectionStart;
        final int selectionEnd = draftSelectionEnd;
        repository.saveDraft(new ConversationDraft(conversation, instance, revision, text,
                selectionStart, selectionEnd, System.currentTimeMillis()), result -> {
            if (!conversation.equals(conversationId)) return;
            // 旧 instance 的迟到 callback 不得修改提交后新开的草稿生命周期。
            if (!instance.equals(draftInstanceId)) return;
            Integer code = result.isSuccess() ? result.value : null;
            if (code == null) {
                draftDirty = true; // 传输失败：保持脏，下一次变化/flush 重试
                return;
            }
            if (code == MatrixErrorCode.INVALID_STATE) {
                // instance 已被提交消费（如并发 PTT final）→ 换新 instance 重存当前内容
                draftInstanceId = UUID.randomUUID().toString();
                draftRevision = 0;
                draftDirty = true;
                flushDraft();
                return;
            }
            if (code == MatrixErrorCode.SUCCESS) {
                draftRevision = Math.max(draftRevision, revision);
            } else {
                draftDirty = true; // INVALID_ARGUMENT（超限等）：保留内存值待用户缩短
            }
        });
    }

    /**
     * 统一提交（§3.2）：冻结一个精确的草稿 instance/revision 快照并立即切到新的
     * 编辑生命周期。提交、旧保存与 Host tombstone 均以这个 snapshot 为键，因此任何
     * 迟到保存都不能在受理后“复活”已发送内容；receipt 到达时也不会误清后续新编辑。
     *
     * <p>附件（I6）：READY 草稿附件随本次提交携带——受理后 Host 冻结链接（不可再删），
     * 本地 chips 清空；失败恢复时 chips 保留（附件仍为草稿态，可继续编辑重试）。</p>
     */
    public void submitUnified(@NonNull String rawText) {
        String text = rawText == null ? "" : rawText.strip();
        List<String> attachmentIds = readyAttachmentIds();
        if ((text.isEmpty() && attachmentIds.isEmpty()) || conversationId == null || sending) {
            Log.w(TAG, "[ConversationUi] submit ignored empty=" + text.isEmpty()
                    + " hasConversation=" + (conversationId != null) + " sending=" + sending);
            return;
        }
        Log.i(TAG, "[ConversationUi] submit unified chars=" + text.length()
                + " attachments=" + attachmentIds.size()
                + " conversation=" + conversationId);
        draftSaveGeneration++; // 取消尚未入 lane 的 debounce
        PendingDraftSubmission reusable = uncertainSubmission;
        boolean retryingUncertain = reusable != null
                && reusable.conversationId().equals(conversationId)
                && reusable.text().equals(text)
                && reusable.contextAttachmentIds().equals(attachmentIds);
        PendingDraftSubmission submitted = retryingUncertain ? reusable
                : new PendingDraftSubmission(conversationId, text,
                        draftLoaded ? new ConversationDraft(conversationId, draftInstanceId,
                                draftRevision, draftText, draftSelectionStart, draftSelectionEnd,
                                System.currentTimeMillis()) : null,
                        UUID.randomUUID().toString(), attachmentIds);
        if (!retryingUncertain) uncertainSubmission = null;
        pendingDraftSubmission = submitted;
        // 在排队 Binder 调用前轮换 instance。UI 会被冻结直到 receipt，因而用户不可能
        // 在“旧提交未定、新编辑已发生”的间隙把新文本误归属到旧 instance。
        draftInstanceId = UUID.randomUUID().toString();
        draftRevision = 0L;
        draftText = "";
        draftSelectionStart = 0;
        draftSelectionEnd = 0;
        draftDirty = false;
        draftRestores.setValue(new DraftRestore("", 0, 0));
        sending = true;
        publish(null);
        repository.submitTextOrAppend(conversationId, text, attachmentIds, submitted.draft(),
                submitted.operationId(),
                result -> {
                    if (pendingDraftSubmission != submitted) return;
                    pendingDraftSubmission = null;
                    sending = false;
                    if (!result.isSuccess() || result.value == null) {
                        Log.w(TAG, "[ConversationUi] unified transport failed");
                        publish("发送失败：Host 未连接");
                        uncertainSubmission = submitted;
                        restoreDraftAfterFailure(submitted);
                        return;
                    }
                    ConversationSubmission submission = result.value;
                    // 运行时明确拒绝将本次输入并入宿主任务：这不是 Binder 不确定性，
                    // 不能重用 operationId，也不能吞掉用户仍可修订的草稿。
                    if (submission.outcome
                            == ConversationSubmission.OUTCOME_STEER_DELIVERY_FAILED) {
                        publish("追加未并入执行中的请求，请修订后重试");
                        uncertainSubmission = null;
                        restoreDraftAfterFailure(submitted);
                        return;
                    }
                    if (submission.code != MatrixErrorCode.SUCCESS) {
                        Log.w(TAG, "[ConversationUi] unified rejected code="
                                + submission.code);
                        publish("发送被拒绝（错误码：" + submission.code + "）");
                        // SERVICE_NOT_READY 代表 Binder/连接语义不确定；下一次同文本提交
                        // 必须重放同一个 operationId，不能生成新任务。
                        uncertainSubmission = submission.code == MatrixErrorCode.SERVICE_NOT_READY
                                ? submitted : null;
                        restoreDraftAfterFailure(submitted);
                        return;
                    }
                    Log.i(TAG, "[ConversationUi] unified accepted outcome="
                            + submission.outcome);
                    // 受理（PRIMARY/STEER）：Host 已 tombstone submitted.draft()、
                    // 冻结附件链接；当前空白 instance 已在提交前创建。
                    submittedAttachments.clear();
                    uncertainSubmission = null;
                    refreshDraftAttachments();
                    publish(null);
                });
    }

    private void restoreDraftAfterFailure(PendingDraftSubmission submitted) {
        ConversationDraft prior = submitted.draft();
        // draftLoaded=false（极早期输入）没有可恢复的 Host 草稿，仍保留用户可见正文。
        draftInstanceId = prior == null ? UUID.randomUUID().toString() : prior.draftInstanceId;
        draftRevision = prior == null ? 0L : prior.revision;
        draftText = submitted.text();
        draftSelectionStart = prior == null ? draftText.length()
                : clampSelection(prior.selectionStart, draftText);
        draftSelectionEnd = prior == null ? draftText.length()
                : clampSelection(prior.selectionEnd, draftText);
        draftDirty = true;
        draftRestores.setValue(new DraftRestore(draftText, draftSelectionStart,
                draftSelectionEnd));
        // 附件保持草稿态（Host 侧未冻结）；本地列表刷新即恢复 chips。
        refreshDraftAttachments();
        flushDraft();
    }

    private record PendingDraftSubmission(String conversationId, String text,
            ConversationDraft draft, String operationId,
            List<String> contextAttachmentIds) { }

    private static int clampSelection(int value, String text) {
        return Math.max(0, Math.min(value, text.length()));
    }

    // ---------------------------------------------------------------- 内部

    private void subscribeCurrent() {
        closeSubscription();
        // 新订阅前清空本地运行阶段（I3 §6.2）：Host 快照到达前不显示旧会话残影。
        runtimeStage = null;
        String target = conversationId;
        subscription = repository.subscribe(target, new ConversationListener() {
            @Override public void onMessageUpsert(ConversationMessage message) {
                if (target.equals(message.conversationId)) {
                    merge(message);
                    if (message.channel == ConversationMessage.CHANNEL_PTT
                            && message.role == ConversationMessage.ROLE_USER) {
                        // PTT 用户消息已落库：识别-提交闭环完成，状态条随之收敛。
                        // 只收敛 PROCESSING 观察态——对话订阅不带 attempt 门卫，用户在
                        // 上一轮消息落库前又开始新一轮按压时，不得误伤新轮的 ARMING。
                        liveTranscript = null;
                        if (pttPhase == PttPhase.PROCESSING) {
                            transitionPtt(PttEvent.TERMINATED);
                        }
                    }
                    publish(null);
                }
            }

            @Override public void onMessageStatusChanged(String conversationId,
                    String messageId, int status, int errorCode) {
                if (!target.equals(conversationId)) return;
                for (UiMessage existing : bySequence.values()) {
                    if (existing.messageId().equals(messageId)) {
                        bySequence.put(existing.sequence(),
                                existing.withStatus(status, errorCode));
                        clearStageIfTerminal(existing.conversationTaskId(), status);
                        publish(null);
                        return;
                    }
                }
            }

            @Override public void onRuntimeStageChanged(ConversationRuntimeStage stage) {
                if (stage == null || !target.equals(stage.conversationId)) return;
                // 迟到事件丢弃：同任务 generation 只进不退（快照与实时同代允许覆盖）。
                if (runtimeStage != null
                        && stage.conversationTaskId.equals(runtimeStage.conversationTaskId)
                        && stage.generation < runtimeStage.generation) {
                    return;
                }
                runtimeStage = stage;
                publish(null);
            }

            @Override public void onConversationInfoChanged(ConversationInfo info) {
                if (info == null || !target.equals(info.conversationId)) return;
                conversationTitle = displayTitle(info);
                publish(null);
            }

            @Override public void onConversationError(String conversationId, int errorCode) {
                if (target.equals(conversationId)) {
                    publish("会话事件异常（错误码：" + errorCode + "）");
                }
            }
        }, result -> {
            if (!result.isSuccess()) {
                publish("订阅建立失败，将以刷新恢复");
            }
        });
        subscribeDebug(target);
    }

    private void subscribeDebug(String target) {
        if (!BuildConfig.MATRIX_DEBUG_TRACE_UI) return;
        debugSubscription = repository.subscribeDebug(event -> {
            if (!target.equals(event.conversationId) || event.hostUserMessageId == null) return;
            mergeDebugEvent(event);
            publish(null);
        }, result -> {
            // Host 端开关不同/旧 Host 都属于正常降级：对话主链路不能为此报错。
        });
    }

    private void loadHistory() {
        if (conversationId == null) return;
        repository.pageMessages(conversationId, -1L, result -> {
            if (!result.isSuccess() || result.value == null) {
                publish("历史加载失败：Host 未连接");
                return;
            }
            bySequence.clear();
            for (ConversationMessage message : result.value.messages) {
                merge(message);
            }
            hasMoreHistory = result.value.hasMore;
            refreshTitleAndMarker();
            publish(null);
        });
    }

    private void merge(ConversationMessage message) {
        // SDK DTO 是显式字段（非 record 访问器）；v5 元数据（steer 注记 + 轨迹）随行
        UiMessage mapped = new UiMessage(message.messageId, message.sequenceNo, message.role,
                message.status, message.channel, message.text, message.failureCode,
                message.inputKind, message.steerHostUserMessageId,
                message.steerDeliveryState, message.conversationTaskId,
                message.executionTraces == null ? List.of() : message.executionTraces,
                debugTracesFor(message));
        // Host 的 upsert 可能只补充 capability trace / steer 投递态；不能仅比较 status/text，
        // 否则 UI 会错过同序号的后续事实。调试轨迹同样随本行不可变快照带入。
        bySequence.put(mapped.sequence(), mapped);
        if (BuildConfig.MATRIX_DEBUG_TRACE_UI
                && message.role == ConversationMessage.ROLE_USER
                && message.inputKind == ConversationMessage.INPUT_PRIMARY) {
            requestDebugHistoryOnce(message.messageId);
        }
        // 运行阶段（I3）：终态消息 upsert 替代阶段显示（Host 侧先推 upsert 再 clear）。
        clearStageIfTerminal(message.conversationTaskId, message.status);
        recomputeRunningFlag();
    }

    /** 阶段指向的任务到达终态 → 立即隐藏输入栏运行状态（§6.2 顺序契约的客户端半边）。 */
    private void clearStageIfTerminal(String conversationTaskId, int status) {
        if (runtimeStage == null || conversationTaskId == null) return;
        if (!conversationTaskId.equals(runtimeStage.conversationTaskId)) return;
        if (status != ConversationMessage.STATUS_ACCEPTED
                && status != ConversationMessage.STATUS_RUNNING) {
            runtimeStage = null;
        }
    }

    private static String displayTitle(ConversationInfo info) {
        return info.title == null || info.title.isBlank() ? "未命名对话" : info.title;
    }

    private void requestDebugHistoryOnce(String hostUserMessageId) {
        if (!debugHistoryRequested.add(hostUserMessageId)) return;
        repository.loadDebugHistory(hostUserMessageId, result -> {
            if (!result.isSuccess() || result.value == null) return;
            for (DebugTraceWireEvent event : result.value) mergeDebugEvent(event);
            publish(null);
        });
    }

    private void mergeDebugEvent(DebugTraceWireEvent event) {
        if (event == null || event.hostUserMessageId == null) return;
        List<DebugTraceWireEvent> existing = new ArrayList<>(
                debugByHostMessage.getOrDefault(event.hostUserMessageId, List.of()));
        String identity = event.traceId + ":" + event.partIndex;
        for (DebugTraceWireEvent item : existing) {
            if ((item.traceId + ":" + item.partIndex).equals(identity)) return;
        }
        existing.add(event);
        existing.sort(Comparator.comparingLong((DebugTraceWireEvent item) -> item.timestampMs)
                .thenComparingLong(item -> item.eventSequence)
                .thenComparingInt(item -> item.partIndex));
        debugByHostMessage.put(event.hostUserMessageId, List.copyOf(existing));
        for (UiMessage message : new ArrayList<>(bySequence.values())) {
            // 过程流的锚点是本轮 USER message。这样 Host 在最终 ASSISTANT message
            // 产生前推送的事件也能立即渲染，绝不能等待任务结束后才“补到回复里”。
            if (event.hostUserMessageId.equals(message.messageId())) {
                bySequence.put(message.sequence(), message.withDebugTraces(existing));
            }
        }
    }

    private List<DebugTraceWireEvent> debugTracesFor(ConversationMessage message) {
        if (message.role == ConversationMessage.ROLE_USER) {
            return debugByHostMessage.getOrDefault(message.messageId, List.of());
        }
        // 最终答复只承载最终文本；过程由它前面的独立 process card 呈现。
        return List.of();
    }

    private void recomputeRunningFlag() {
        for (UiMessage message : bySequence.values()) {
            if (message.role() == ConversationMessage.ROLE_USER
                    && !isTerminal(message.status())) {
                hasRunningTask = true;
                return;
            }
        }
        hasRunningTask = false;
    }

    private static boolean isTerminal(int status) {
        return status != ConversationMessage.STATUS_ACCEPTED
                && status != ConversationMessage.STATUS_RUNNING;
    }

    /** 唯一状态发布口：transientError 一次性消费（下一次成功发布清除）。 */
    private synchronized void publish(String error) {
        List<UiMessage> all = new ArrayList<>(bySequence.values());
        if (all.size() > MAX_RENDERED) {
            all = new ArrayList<>(all.subList(all.size() - MAX_RENDERED, all.size()));
        }
        state.postValue(new State(conversationId, List.copyOf(all), sending, loadingHistory,
                hasMoreHistory, error, hasRunningTask, pttPhase,
                liveTranscript, summaryActive, conversationTitle, runtimeStage,
                submittedAttachments, modelRuntime));
    }

    // ---------------------------------------------------------------- 附件与模型胶囊（I6/I5）

    /**
     * 摄取附件：结果以 notice 呈现（成功“已添加：文件名”/失败原因），
     * chips 列表经 refreshDraftAttachments 从 Host 拉回（单一事实源）。
     */
    public void stageAttachment(@NonNull android.os.ParcelFileDescriptor fd,
            @NonNull String declaredMime, @NonNull String displayName,
            @NonNull java.util.function.Consumer<String> notice) {
        if (conversationId == null) {
            notice.accept("附件需在会话就绪后添加");
            return;
        }
        repository.stageAttachment(conversationId, fd, declaredMime, displayName,
                UUID.randomUUID().toString(), result -> {
                    if (!result.isSuccess() || result.value == null) {
                        notice.accept("Host 未就绪或未通告附件域");
                        return;
                    }
                    com.matrix.agent.api.conversation.ConversationAttachment attachment =
                            result.value;
                    if (attachment.isReady()) {
                        notice.accept(attachment.safeDisplayName);
                    } else if (attachment.isStaging()) {
                        notice.accept("正在解析：" + attachment.safeDisplayName);
                    } else {
                        notice.accept(attachmentErrorCodeText(attachment.errorCode));
                    }
                    refreshDraftAttachments();
                });
    }

    /** chip 的 ×：仅删草稿附件（已冻结的 Host 会拒绝并如实返回 INVALID_STATE）。 */
    public void deleteAttachment(@NonNull String attachmentId) {
        repository.deleteAttachment(attachmentId, result -> {
            if (result.isSuccess() && result.value != null
                    && result.value == MatrixErrorCode.SUCCESS) {
                refreshDraftAttachments();
            } else {
                publish("附件删除未生效（可能已随消息提交）");
            }
        });
    }

    /** 从 Host 拉回本会话草稿附件（进入/切换/提交后恢复 chips）。 */
    public void refreshDraftAttachments() {
        if (conversationId == null || attachmentRefreshInFlight) return;
        attachmentRefreshInFlight = true;
        final String target = conversationId;
        repository.listDraftAttachments(target, result -> {
            attachmentRefreshInFlight = false;
            if (!target.equals(conversationId)) return;
            List<com.matrix.agent.api.conversation.ConversationAttachment> next =
                    result.isSuccess() && result.value != null
                            ? List.copyOf(result.value) : List.of();
            // 等值跳过：Host 投影未变不重发 State（chips 无谓重建）。
            if (!sameAttachments(submittedAttachments, next)) {
                submittedAttachments = next;
                // 任何 chip 集合变化都意味着“原请求”的上下文已不再相同，不能重放它。
                PendingDraftSubmission retry = uncertainSubmission;
                if (retry != null && !retry.contextAttachmentIds().equals(readyAttachmentIds())) {
                    uncertainSubmission = null;
                }
                publish(null);
            }
            boolean hasStaging = next.stream().anyMatch(
                    com.matrix.agent.api.conversation.ConversationAttachment::isStaging);
            if (hasStaging && attachmentStagingPolls++ < MAX_ATTACHMENT_STAGING_POLLS) {
                mainHandler.postDelayed(this::refreshDraftAttachments, 350L);
            } else if (!hasStaging) {
                attachmentStagingPolls = 0;
            }
        });
    }

    /** 模型胶囊（I5 §8.1）：数据唯一来自 getRuntimeStatus；连接建立/页面进入时拉取。 */
    public void refreshModelRuntime() {
        repository.modelRuntimeStatus(result -> {
            com.matrix.agent.api.model.ModelRuntimeStatus next =
                    result.isSuccess() ? result.value : null;
            if (next != modelRuntime) {
                modelRuntime = next;
                publish(null);
            }
        });
    }

    /** READY 附件 id（提交携带；FAILED 由 Host 在提交前拒绝，UI 已先行隐藏提交资格）。 */
    private List<String> readyAttachmentIds() {
        if (submittedAttachments.isEmpty()) return List.of();
        List<String> ids = new ArrayList<>(submittedAttachments.size());
        for (com.matrix.agent.api.conversation.ConversationAttachment attachment
                : submittedAttachments) {
            if (attachment.isReady()) ids.add(attachment.attachmentId);
        }
        return List.copyOf(ids);
    }

    private static String attachmentErrorCodeText(int errorCode) {
        if (errorCode == com.matrix.agent.api.conversation.ConversationAttachment
                .ERROR_TOO_LARGE) return "超过 2 MiB 上限";
        if (errorCode == com.matrix.agent.api.conversation.ConversationAttachment
                .ERROR_EMPTY_TEXT) return "文件无有效文本";
        if (errorCode == MatrixErrorCode.OVERLOADED
                || errorCode == MatrixErrorCode.TASK_FAILED) return "解析中断，请重新添加";
        return "仅支持文本类附件";
    }

    private static boolean sameAttachments(
            List<com.matrix.agent.api.conversation.ConversationAttachment> left,
            List<com.matrix.agent.api.conversation.ConversationAttachment> right) {
        if (left.size() != right.size()) return false;
        for (int index = 0; index < left.size(); index++) {
            com.matrix.agent.api.conversation.ConversationAttachment a = left.get(index);
            com.matrix.agent.api.conversation.ConversationAttachment b = right.get(index);
            if (!java.util.Objects.equals(a.attachmentId, b.attachmentId)
                    || a.state != b.state) {
                return false;
            }
        }
        return true;
    }
}
