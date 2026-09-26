package com.matrix.agent.launcher.overlay;

import com.matrix.agent.api.conversation.*;
import com.matrix.agent.launcher.data.ConversationRepository;
import com.matrix.agent.launcher.presentation.ConversationViewModel.UiMessage;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import com.matrix.agent.api.debug.DebugTraceWireEvent;
import com.matrix.agent.launcher.BuildConfig;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** A full conversation timeline with an independently bound operation/primary-message owner. */
public final class OverlayConversationPresenter implements AutoCloseable {
    public record State(String title, String progress, List<UiMessage> messages, int status, boolean connected,
            boolean cancelling, String notice, boolean hasMoreHistory, boolean loadingHistory, String historyError) {}
    private final com.matrix.agent.launcher.data.OverlayConversationSource repository;
    private final Runnable changed;
    private final Map<String, UiMessage> messages = new LinkedHashMap<>();
    private final Map<String, Long> updatedAt = new LinkedHashMap<>();
    private record StatusUpdate(int status, int error) {}
    private final Map<String, StatusUpdate> pendingStatuses = new LinkedHashMap<>();
    private boolean historyLoaded, hasMoreHistory, loadingHistory;
    private String historyError = "";
    private long historyQuery, historyBefore = -1;
    private OverlayBinding binding;
    private AutoCloseable subscription, debugSubscription;
    private final Map<String, List<DebugTraceWireEvent>> debugByMessage = new LinkedHashMap<>();
    private final Set<String> debugHistoryRequested = new HashSet<>();
    private boolean closed;
    private boolean connected;
    private boolean cancelling;
    private boolean cancellationNotice;
    private boolean anchorReadInFlight;
    private boolean subscriptionFailed;
    private String title = "Agent 任务", notice = "";
    private int status = -1;
    private long stageGeneration = -1, subscriptionVersion, latestSequence;
    private ConversationRuntimeStage stage;

    public OverlayConversationPresenter(com.matrix.agent.launcher.data.OverlayConversationSource repository, OverlayBinding binding,
            Runnable changed) {
        this.repository = repository; this.binding = binding; this.changed = changed;
    }
    public OverlayBinding binding() { return binding; }
    public void authenticateRuntime(OverlayBinding value) { if (binding.sameRound(value)) binding = value; }
    public int status() { return status; }
    public boolean terminal() { return terminal(status); }
    public static boolean terminal(int value) {
        return value == ConversationMessage.STATUS_COMPLETED || value == ConversationMessage.STATUS_FAILED
                || value == ConversationMessage.STATUS_CANCELLED || value == ConversationMessage.STATUS_REJECTED
                || value == ConversationMessage.STATUS_EXECUTION_UNKNOWN;
    }
    public void start(Consumer<Boolean> ready) {
        closeSubscription();
        long version = ++subscriptionVersion;
        long recoveryCursor = latestSequence;
        debugHistoryRequested.clear();
        stageGeneration = -1; stage = null;
        subscriptionFailed = false;
        loadingHistory = false; ++historyQuery;
        subscription = repository.subscribe(binding.conversationId(), new ConversationRepository.ConversationListener() {
            private boolean current() { return !closed && version == subscriptionVersion; }
            @Override public void onMessageUpsert(ConversationMessage message) { if (current()) merge(message); }
            @Override public void onMessageStatusChanged(String conv, String id, int value, int error) {
                if (!current() || !binding.conversationId().equals(conv)) return;
                UiMessage row = messages.get(id);
                if (row != null) {
                    if (terminal(row.status()) && !terminal(value)) return;
                    messages.put(id, row.withStatus(value, error));
                } else pendingStatuses.put(id, new StatusUpdate(value, error));
                if (binding.userMessageId().equals(id)) {
                    if (terminal(status) && !terminal(value)) { refreshAnchor(); return; }
                    status = value;
                    if (terminal(value)) finishTerminal();
                }
                changed.run();
            }
            @Override public void onRuntimeStageChanged(ConversationRuntimeStage value) {
                if (!current() || terminal() || !binding.taskId().equals(value.conversationTaskId)
                        || value.generation <= stageGeneration) return;
                stageGeneration = value.generation; stage = value; changed.run();
            }
            @Override public void onConversationInfoChanged(ConversationInfo info) {
                if (current() && binding.conversationId().equals(info.conversationId)) {
                    title = info.title == null ? "Agent 任务" : info.title; changed.run();
                }
            }
            @Override public void onConversationError(String id, int error) {
                if (current()) {
                    subscriptionFailed = true; connected = false;
                    cancellationNotice = false;
                    notice = "会话暂不可用，请返回 Agent 查看"; changed.run();
                }
            }
        }, result -> {
            if (closed || version != subscriptionVersion) return;
            connected = !subscriptionFailed && result.isSuccess() && result.value != null && repository.isHostConnected();
            ready.accept(connected);
            if (connected) {
                if (BuildConfig.MATRIX_DEBUG_TRACE_UI) {
                    debugSubscription = repository.subscribeDebug(event -> {
                        if (!closed && version == subscriptionVersion) mergeDebugEvent(event);
                    }, ignored -> {});
                    new ArrayList<>(messages.values()).forEach(this::requestDebugHistory);
                }
                refreshAnchor(); loadHistory(true);
                if (recoveryCursor > 0) fillGap(version, recoveryCursor);
            }
            changed.run();
        });
    }
    public void setConnected(boolean value) {
        connected = value;
        if (!value) {
            ++subscriptionVersion; ++historyQuery; loadingHistory = false;
            closeSubscription(); stageGeneration = -1; stage = null;
        }
        else start(ignored -> {});
        changed.run();
    }
    /** Carry loaded history across a new round without carrying its operation ownership. */
    public void inheritConversation(OverlayConversationPresenter previous) {
        if (!binding.conversationId().equals(previous.binding.conversationId())) return;
        messages.putAll(previous.messages); updatedAt.putAll(previous.updatedAt);
        debugByMessage.putAll(previous.debugByMessage);
        pendingStatuses.putAll(previous.pendingStatuses);
        latestSequence = previous.latestSequence; title = previous.title;
        historyLoaded = previous.historyLoaded; hasMoreHistory = previous.hasMoreHistory;
        historyBefore = previous.historyBefore;
        UiMessage primary = messages.get(binding.userMessageId());
        if (primary != null) status = primary.status();
    }
    public void merge(ConversationMessage message) {
        if (mergeRow(message)) changed.run();
    }
    private boolean mergeRow(ConversationMessage message) {
        if (closed || message == null || !binding.conversationId().equals(message.conversationId)) return false;
        UiMessage old = messages.get(message.messageId);
        if (old != null && (updatedAt.getOrDefault(message.messageId, 0L) > message.updatedAtMs
                || (terminal(old.status()) && !terminal(message.status)))) return false;
        latestSequence = Math.max(latestSequence, message.sequenceNo);
        UiMessage mapped = UiMessage.from(message).withDebugTraces(
                debugByMessage.getOrDefault(message.messageId, List.of()));
        StatusUpdate pending = pendingStatuses.remove(message.messageId);
        if (pending != null && !terminal(mapped.status())) mapped = mapped.withStatus(pending.status(), pending.error());
        messages.put(message.messageId, mapped); updatedAt.put(message.messageId, message.updatedAtMs);
        if (binding.userMessageId().equals(message.messageId)) {
            if (!terminal(status) || terminal(mapped.status())) status = mapped.status();
            if (terminal()) finishTerminal();
        }
        requestDebugHistory(mapped);
        return true;
    }
    private void requestDebugHistory(UiMessage row) {
        if (!BuildConfig.MATRIX_DEBUG_TRACE_UI || !connected
                || row.role() != ConversationMessage.ROLE_USER || !debugHistoryRequested.add(row.messageId())) return;
        long version = subscriptionVersion;
        repository.loadDebugHistory(row.messageId(), result -> {
            if (closed || version != subscriptionVersion) return;
            if (!result.isSuccess() || result.value == null) {
                debugHistoryRequested.remove(row.messageId()); return;
            }
            result.value.forEach(this::mergeDebugEvent);
        });
    }
    private void mergeDebugEvent(DebugTraceWireEvent event) {
        if (event == null || !binding.conversationId().equals(event.conversationId)
                || event.hostUserMessageId == null) return;
        List<DebugTraceWireEvent> events = new ArrayList<>(
                debugByMessage.getOrDefault(event.hostUserMessageId, List.of()));
        if (events.stream().anyMatch(old -> old.traceId.equals(event.traceId) && old.partIndex == event.partIndex)) return;
        events.add(event);
        events.sort(Comparator.comparingLong((DebugTraceWireEvent item) -> item.timestampMs)
                .thenComparingLong(item -> item.eventSequence).thenComparingInt(item -> item.partIndex));
        List<DebugTraceWireEvent> snapshot = List.copyOf(events);
        debugByMessage.put(event.hostUserMessageId, snapshot);
        UiMessage row = messages.get(event.hostUserMessageId);
        if (row != null && row.role() == ConversationMessage.ROLE_USER) {
            messages.put(row.messageId(), row.withDebugTraces(snapshot)); changed.run();
        }
    }
    public void loadOlder() { loadHistory(!historyLoaded); }
    private void loadHistory(boolean latest) {
        if (closed || !connected || loadingHistory || (!latest && !hasMoreHistory && historyError.isEmpty())) return;
        // Anchor/snapshot rows may lie outside the contiguous page range. They cannot move
        // its cursor, or an older primary anchor would make history paging skip a gap.
        long before = latest ? -1 : historyBefore;
        long query = ++historyQuery, version = subscriptionVersion;
        loadingHistory = true; historyError = ""; changed.run();
        repository.pageMessages(binding.conversationId(), before, result -> {
            if (closed || query != historyQuery || version != subscriptionVersion) return;
            loadingHistory = false;
            if (!result.isSuccess() || result.value == null) {
                historyError = "消息加载失败，点按重试"; changed.run(); return;
            }
            ConversationPage page = result.value;
            long first = page.messages.stream().filter(m -> binding.conversationId().equals(m.conversationId))
                    .mapToLong(m -> m.sequenceNo).min().orElse(-1);
            page.messages.forEach(this::mergeRow);
            if (!latest || !historyLoaded || historyBefore < 0 || (first >= 0 && first <= historyBefore)) {
                historyBefore = first;
                hasMoreHistory = page.hasMore && first >= 0 && (latest || first < before);
            }
            historyLoaded = true;
            changed.run();
        });
    }
    public void refreshAnchor() {
        if (anchorReadInFlight || closed) return;
        anchorReadInFlight = true;
        long version = subscriptionVersion;
        repository.messagesAround(binding.conversationId(), binding.userSequence(), result -> {
            anchorReadInFlight = false;
            if (closed) return;
            if (version != subscriptionVersion) { if (connected) refreshAnchor(); return; }
            if (!result.isSuccess() || result.value == null) return;
            if (result.value.anchorExists) {
                result.value.messages.forEach(this::mergeRow); changed.run();
            }
        });
    }
    private void fillGap(long version, long after) {
        repository.messagesAfter(binding.conversationId(), after, result -> {
            if (closed || version != subscriptionVersion || !result.isSuccess() || result.value == null) return;
            result.value.messages.forEach(this::mergeRow); changed.run();
            long next = result.value.messages.stream().mapToLong(m -> m.sequenceNo).max().orElse(after);
            if (result.value.hasAfter && next > after) fillGap(version, next);
        });
    }
    public void cancel() {
        if (closed || terminal() || cancelling) return;
        cancelling = true; cancellationNotice = true; notice = "正在取消"; changed.run();
        repository.cancelMessage(binding.conversationId(), binding.userMessageId(),
                java.util.UUID.randomUUID().toString(), result -> {
            if (closed || terminal()) return;
            if (!result.isSuccess() || result.value == null || result.value.code != 0) {
                cancelling = false; cancellationNotice = true; notice = "取消尚未确认，请稍后重试";
            }
            refreshAnchor(); changed.run();
        });
    }
    private void finishTerminal() {
        cancelling = false; stage = null;
        if (cancellationNotice) { cancellationNotice = false; notice = ""; }
    }
    public void notice(String value) { cancellationNotice = false; notice = value; changed.run(); }
    public State state() {
        String progress = cancelling ? "正在取消" : switch (status) {
            case ConversationMessage.STATUS_COMPLETED -> "已完成";
            case ConversationMessage.STATUS_CANCELLED -> "已取消";
            case ConversationMessage.STATUS_FAILED, ConversationMessage.STATUS_REJECTED -> "执行失败";
            case ConversationMessage.STATUS_EXECUTION_UNKNOWN -> "执行结果待确认";
            default -> stage == null ? "正在同步任务" : switch (stage.stage) {
                case ConversationRuntimeStage.STAGE_QUEUED -> "等待执行";
                case ConversationRuntimeStage.STAGE_PLANNING -> "正在规划";
                case ConversationRuntimeStage.STAGE_EXECUTING -> stage.safeLabel.isBlank()
                        ? "正在执行" : stage.safeLabel;
                default -> "正在运行";
            };
        };
        List<UiMessage> timeline = messages.values().stream()
                .sorted(Comparator.comparingLong(UiMessage::sequence).thenComparing(UiMessage::messageId))
                .collect(java.util.stream.Collectors.toList());
        return new State(title, progress, List.copyOf(timeline), status, connected, cancelling, notice,
                hasMoreHistory, loadingHistory, historyError);
    }
    private void closeSubscription() {
        if (debugSubscription != null) {
            try { debugSubscription.close(); } catch (Exception ignored) { }
            debugSubscription = null;
        }
        if (subscription != null) {
            try { subscription.close(); } catch (Exception ignored) { }
            subscription = null;
        }
    }
    @Override public void close() { closed = true; ++subscriptionVersion; closeSubscription(); }
}
