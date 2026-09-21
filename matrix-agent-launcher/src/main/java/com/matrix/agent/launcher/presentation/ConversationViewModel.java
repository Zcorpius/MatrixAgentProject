package com.matrix.agent.launcher.presentation;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.matrix.agent.api.common.MatrixErrorCode;
import com.matrix.agent.api.conversation.ConversationInfo;
import com.matrix.agent.api.conversation.ConversationMessage;
import com.matrix.agent.api.debug.DebugTraceWireEvent;
import com.matrix.agent.launcher.BuildConfig;
import com.matrix.agent.launcher.data.ConversationRepository.ConversationListener;
import com.matrix.agent.launcher.data.ConversationRepository;

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

    public static final class State {
        public final String conversationId;
        public final List<UiMessage> messages;
        public final boolean sending;
        public final boolean loadingHistory;
        public final boolean hasMoreHistory;
        public final String transientError;
        public final boolean hasRunningTask;
        public final boolean recording;
        public final String liveTranscript;
        /** 摘要续聊标记（读时重算；评估 v1.0 §4.6）。 */
        public final boolean summaryActive;
        /** 当前会话标题（自动/用户来源由 Host 维护）。 */
        public final String conversationTitle;

        State(String conversationId, List<UiMessage> messages, boolean sending,
                boolean loadingHistory, boolean hasMoreHistory, String transientError,
                boolean hasRunningTask, boolean recording, String liveTranscript,
                boolean summaryActive, String conversationTitle) {
            this.conversationId = conversationId;
            this.messages = messages;
            this.sending = sending;
            this.loadingHistory = loadingHistory;
            this.hasMoreHistory = hasMoreHistory;
            this.transientError = transientError;
            this.hasRunningTask = hasRunningTask;
            this.recording = recording;
            this.liveTranscript = liveTranscript;
            this.summaryActive = summaryActive;
            this.conversationTitle = conversationTitle;
        }
    }

    private static final int MAX_RENDERED = 200;

    private final ConversationRepository repository;
    private final MutableLiveData<State> state = new MutableLiveData<>(
            new State(null, List.of(), false, false, false, null, false, false, null,
                    false, null));
    /** 渲染缓冲：sequence 升序（TreeMap），事件按 messageId 等值合并。 */
    private final TreeMap<Long, UiMessage> bySequence = new TreeMap<>();

    private String conversationId;
    private AutoCloseable subscription;
    private AutoCloseable debugSubscription;
    private boolean sending;
    private boolean loadingHistory;
    private boolean recording;
    private boolean recordingStarting;
    private boolean stopRequestedWhileStarting;
    private String activeVoiceSessionId;
    private String liveTranscript;
    private boolean hasMoreHistory;
    private boolean hasRunningTask;
    /** Host 已连上但 ConversationGraph 仍在恢复对账时的短暂 gate；只做有界重试。 */
    private int bootstrapRetryCount;
    private boolean summaryActive;
    private String conversationTitle;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /** host userMessageId → 已持久化/实时调试事件；只在 debug build 有内容。 */
    private final Map<String, List<DebugTraceWireEvent>> debugByHostMessage =
            new LinkedHashMap<>();
    private final Set<String> debugHistoryRequested = new HashSet<>();

    public ConversationViewModel(ConversationRepository repository) {
        this.repository = repository;
    }

    public LiveData<State> state() {
        return state;
    }

    public boolean isHostConnected() {
        return repository.isHostConnected();
    }

    /** 页面进入：复用最近未归档线程（否则新建）并订阅。 */
    public void start() {
        if (conversationId != null) {
            subscribeCurrent();
            loadHistory();
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
                publish(null);
            } else {
                repository.createConversation(null, created -> {
                    if (created.isSuccess() && created.value != null) {
                        conversationId = created.value.conversationId;
                        subscribeCurrent();
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
        UiMessage latestUser = null;
        for (UiMessage message : bySequence.values()) {
            if (message.role() == ConversationMessage.ROLE_USER) {
                latestUser = message;
            }
        }
        if (latestUser == null || isTerminal(latestUser.status())) return;
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

    /** PTT 开始录音：创建绑定 → 启动语音会话。 */
    public void startRecording() {
        if (conversationId == null || recording || recordingStarting || !repository.isHostConnected()) {
            return;
        }
        recordingStarting = true;
        publish(null);
        String convId = conversationId;
        String operationId = UUID.randomUUID().toString();
        repository.createVoiceBinding(convId, operationId, bindingResult -> {
            if (!bindingResult.isSuccess() || bindingResult.value == null) {
                recordingStarting = false;
                publish("无法创建语音绑定（Host 未就绪）");
                return;
            }
            String bindingOperationId = bindingResult.value;
            repository.startVoiceSession(bindingOperationId, new ConversationRepository.VoiceInputListener() {
                @Override public void onPartialText(String sessionId, String text) {
                    liveTranscript = text;
                    publish(null);
                }
                @Override public void onFinalText(String sessionId, String text) {
                    liveTranscript = text;
                    publish(null);
                }
                @Override public void onError(String sessionId, int errorCode) {
                    recording = false;
                    recordingStarting = false;
                    activeVoiceSessionId = null;
                    publish("录音会话异常（错误码：" + errorCode + "）");
                }
            }, sessionResult -> {
                recordingStarting = false;
                if (!sessionResult.isSuccess() || sessionResult.value == null) {
                    publish("录音启动失败");
                    return;
                }
                activeVoiceSessionId = sessionResult.value.sessionId;
                recording = true;
                if (stopRequestedWhileStarting) {
                    stopRequestedWhileStarting = false;
                    stopRecording();
                    return;
                }
                publish(null);
            });
        });
    }

    /** PTT 松开：冲刷识别结果 → final 自动进入对话。 */
    public void stopRecording() {
        if (recordingStarting) {
            // 用户很快松手时，Binder 启动回调尚未到；记录意图，拿到 session 后立即 flush，
            // 不能把这次 PTT 静默变成常驻录音。
            stopRequestedWhileStarting = true;
            return;
        }
        if (!recording || activeVoiceSessionId == null) {
            return;
        }
        String sessionId = activeVoiceSessionId;
        recording = false;
        activeVoiceSessionId = null;
        repository.finishRecording(sessionId, UUID.randomUUID().toString(),
                result -> {
                    if (!result.isSuccess() || result.value == null
                            || result.value.code != MatrixErrorCode.SUCCESS) {
                        publish("结束录音失败");
                    } else {
                        publish(null);
                    }
                });
    }

    public boolean isRecording() {
        return recording;
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

    /** 切换会话（列表点击/分支后）。 */
    public void switchConversation(String targetConversationId) {
        if (targetConversationId == null) return;
        conversationId = targetConversationId;
        bySequence.clear();
        debugByHostMessage.clear();
        debugHistoryRequested.clear();
        hasMoreHistory = false;
        conversationTitle = null;
        summaryActive = false;
        subscribeCurrent();
        loadHistory();
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

    // ---------------------------------------------------------------- 内部

    private void subscribeCurrent() {
        closeSubscription();
        String target = conversationId;
        subscription = repository.subscribe(target, new ConversationListener() {
            @Override public void onMessageUpsert(ConversationMessage message) {
                if (target.equals(message.conversationId)) {
                    merge(message);
                    if (message.channel == ConversationMessage.CHANNEL_PTT
                            && message.role == ConversationMessage.ROLE_USER) {
                        liveTranscript = null;
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
                        publish(null);
                        return;
                    }
                }
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
        recomputeRunningFlag();
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
                hasMoreHistory, error, hasRunningTask, recording || recordingStarting,
                liveTranscript, summaryActive, conversationTitle));
    }
}
