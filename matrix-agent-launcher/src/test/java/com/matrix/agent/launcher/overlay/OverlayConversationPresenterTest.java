package com.matrix.agent.launcher.overlay;

import static org.junit.Assert.*;
import com.matrix.agent.api.conversation.*;
import com.matrix.agent.launcher.data.*;
import org.junit.Test;
import java.util.*;
import java.util.function.Consumer;

public final class OverlayConversationPresenterTest {
    private final Source source = new Source();
    private final OverlayBinding binding = new OverlayBinding("runtime", "conv", "task", "primary", 1);
    private final OverlayConversationPresenter presenter = new OverlayConversationPresenter(source, binding, () -> {});
    @Test public void sameSequenceTransitionsAndOldPageCannotRevertTerminal() {
        presenter.merge(row("primary", "task", 0, 0, 1));
        presenter.merge(row("primary", "task", 0, 1, 2)); assertEquals(1, presenter.status());
        presenter.merge(row("primary", "task", 0, 2, 3));
        presenter.merge(row("primary", "task", 0, 1, 4)); assertEquals(2, presenter.status());
    }
    @Test public void timelineContainsPreviousRoundsAndCurrentUserAndAssistantMessages() {
        presenter.merge(row("old-answer", "other-task", 1, 2, 1));
        presenter.merge(row("primary", "task", 0, 1, 2));
        presenter.merge(row("right-answer", "task", 1, 2, 3));
        assertEquals(3, presenter.state().messages().size());
        assertTrue(presenter.state().messages().stream().anyMatch(m -> m.text().equals("old-answer")));
        assertTrue(presenter.state().messages().stream().anyMatch(m -> m.text().equals("primary")));
        assertTrue(presenter.state().messages().stream().anyMatch(m -> m.text().equals("right-answer")));
        assertEquals(1, presenter.status()); // Displaying history must not change the cancellation owner.
    }
    @Test public void cancellationAlwaysTargetsPrimaryMessage() {
        presenter.start(ignored -> {});
        presenter.merge(row("steer", "task", 0, 1, 2));
        presenter.cancel(); assertEquals("primary", source.cancelled);
        assertTrue(presenter.state().cancelling());
    }
    @Test public void reconnectResetsStageGenerationAndRejectsOldSubscription() {
        presenter.start(ignored -> {});
        var old = source.listeners.get(0);
        old.onRuntimeStageChanged(new ConversationRuntimeStage("conv", "task", 100, 3, "旧阶段", 1, false));
        presenter.setConnected(false); presenter.setConnected(true);
        old.onMessageStatusChanged("conv", "primary", 2, 0);
        source.listeners.get(1).onRuntimeStageChanged(new ConversationRuntimeStage("conv", "task", 1, 3, "新阶段", 2, true));
        assertFalse(presenter.terminal()); assertEquals("新阶段", presenter.state().progress());
    }
    @Test public void terminalStatusCallbackBeatsLateRunningSnapshot() {
        presenter.start(ignored -> {});
        source.listeners.get(0).onMessageStatusChanged("conv", "primary", 4, 0);
        presenter.merge(row("primary", "task", 0, 1, 100));
        assertEquals(4, presenter.status());
    }
    @Test public void rejectedSubscriptionCannotBecomeReadyFromReturnedHandle() {
        source.rejectSubscription = true;
        List<Boolean> readiness = new ArrayList<>();
        presenter.start(readiness::add);
        assertEquals(List.of(false), readiness);
        assertFalse(presenter.state().connected());
        source.rejectSubscription = false;
        presenter.setConnected(false); presenter.setConnected(true);
        assertTrue(presenter.state().connected());
    }
    @Test public void cancelledFactClearsPendingNoticeAndBeatsLateRpcFailure() {
        presenter.start(ignored -> {});
        presenter.cancel();
        assertTrue(presenter.state().cancelling());
        source.listeners.get(0).onMessageStatusChanged("conv", "primary", ConversationMessage.STATUS_CANCELLED, 0);
        assertFalse(presenter.state().cancelling());
        assertEquals("已取消", presenter.state().progress());
        assertEquals("", presenter.state().notice());
        source.cancelReply.accept(LauncherHostGateway.Result.failure(new IllegalStateException("late transport failure")));
        assertEquals("已取消", presenter.state().progress());
        assertEquals("", presenter.state().notice());
    }
    @Test public void latestAndOlderPagesUseContiguousHistoryCursorRatherThanPrimaryAnchor() {
        source.latestPage = new ConversationPage(List.of(timeline(20), timeline(21)), true);
        source.olderPage = new ConversationPage(List.of(timeline(18), timeline(19)), true);
        presenter.merge(row("primary", "task", 0, 1, 1)); // anchor outside latest page
        presenter.start(ignored -> {});
        presenter.loadOlder();
        assertEquals(List.of(-1L, 20L), source.pageCursors);
        assertEquals(List.of(1L, 18L, 19L, 20L, 21L), presenter.state().messages().stream()
                .map(m -> m.sequence()).toList());
    }
    @Test public void stalePageCannotOverwriteNewSubscriptionContent() {
        source.holdPages = true;
        presenter.start(ignored -> {});
        presenter.merge(new ConversationMessage("conv", "m20", 20, 1, 2, 1, "新回复", "zh-CN", "old", 0, 1, 20));
        source.pages.get(0).accept(LauncherHostGateway.Result.success(new ConversationPage(List.of(timeline(20)), true)));
        assertEquals("新回复", presenter.state().messages().get(0).text());
    }
    @Test public void duplicateHistoryTapHasOneInFlightRequestAndCanRetryAfterFailure() {
        source.holdPages = true;
        presenter.start(ignored -> {}); presenter.loadOlder(); presenter.loadOlder();
        assertEquals(1, source.pages.size());
        source.pages.get(0).accept(LauncherHostGateway.Result.failure(new IllegalStateException("offline")));
        assertFalse(presenter.state().loadingHistory()); assertFalse(presenter.state().historyError().isEmpty());
        presenter.loadOlder(); assertEquals(2, source.pages.size());
        source.pages.get(1).accept(LauncherHostGateway.Result.success(new ConversationPage(List.of(timeline(20)), false)));
        assertEquals("", presenter.state().historyError());
    }
    @Test public void oldConnectionPageCannotMutateReconnectedTimeline() {
        source.holdPages = true;
        presenter.start(ignored -> {});
        presenter.setConnected(false); presenter.setConnected(true);
        source.pages.get(0).accept(LauncherHostGateway.Result.success(new ConversationPage(List.of(timeline(1)), false)));
        assertTrue(presenter.state().messages().isEmpty()); assertTrue(presenter.state().loadingHistory());
        source.pages.get(1).accept(LauncherHostGateway.Result.success(new ConversationPage(List.of(timeline(2)), false)));
        assertEquals("m2", presenter.state().messages().get(0).messageId());
    }
    @Test public void newRoundKeepsPreviouslyLoadedConversationButOwnsItsOwnCancellation() {
        source.latestPage = new ConversationPage(List.of(timeline(20)), true);
        presenter.start(ignored -> {});
        presenter.merge(row("primary", "task", 0, 2, 3));
        var next = new OverlayConversationPresenter(source,
                new OverlayBinding("runtime2", "conv", "task2", "primary2", 22), () -> {});
        next.inheritConversation(presenter); next.start(ignored -> {});
        assertEquals(presenter.state().messages(), next.state().messages());
        next.cancel(); assertEquals("primary2", source.cancelled);
    }
    @Test public void allMessageStatusesUpdateAndTerminalRowsNeverRollBack() {
        presenter.start(ignored -> {});
        presenter.merge(timeline(20));
        source.listeners.get(0).onMessageStatusChanged("conv", "m20", ConversationMessage.STATUS_FAILED, 7);
        presenter.merge(timeline(20));
        assertEquals(ConversationMessage.STATUS_FAILED, presenter.state().messages().get(0).status());
        assertEquals(7, presenter.state().messages().get(0).failureCode());
    }
    @Test public void longHistoryIsNotSilentlyDroppedAndRemainsImmutable() {
        for (int i = 1; i <= 150; i++) presenter.merge(timeline(i));
        assertEquals(150, presenter.state().messages().size());
        assertEquals(1, presenter.state().messages().get(0).sequence());
        assertThrows(UnsupportedOperationException.class, () -> presenter.state().messages().clear());
    }
    @Test public void endOfHistoryPreventsMoreRequests() {
        source.latestPage = new ConversationPage(List.of(timeline(20)), false);
        presenter.start(ignored -> {}); presenter.loadOlder();
        assertEquals(List.of(-1L), source.pageCursors);
    }
    @Test public void processHistoryAndLiveEventsMergeWithoutDuplicatesOrForeignConversations() {
        org.junit.Assume.assumeTrue(com.matrix.agent.launcher.BuildConfig.MATRIX_DEBUG_TRACE_UI);
        presenter.start(ignored -> {});
        presenter.merge(row("primary", "task", 0, 1, 1));
        var event = trace("conv", "a", 1);
        source.debugListeners.get(0).onDebugTrace(event);
        source.debugListeners.get(0).onDebugTrace(event);
        source.debugListeners.get(0).onDebugTrace(trace("other", "b", 2));
        assertEquals(List.of(event), presenter.state().messages().get(0).debugTraces());
        presenter.merge(row("primary", "task", 0, 2, 2));
        assertEquals(List.of(event), presenter.state().messages().get(0).debugTraces());
        presenter.setConnected(false);
        source.debugListeners.get(0).onDebugTrace(trace("conv", "late", 3));
        assertEquals(1, presenter.state().messages().get(0).debugTraces().size());
        assertEquals(1, source.closedDebugSubscriptions);
        presenter.setConnected(true);
        source.debugListeners.get(1).onDebugTrace(trace("conv", "new", 4));
        assertEquals(2, presenter.state().messages().get(0).debugTraces().size());
    }
    private static com.matrix.agent.api.debug.DebugTraceWireEvent trace(String conversation, String id, long sequence) {
        return new com.matrix.agent.api.debug.DebugTraceWireEvent(sequence, "MODEL_REASONING", "task", id,
                sequence, 0, 1, "{}", conversation, "task", "primary");
    }
    private static ConversationMessage timeline(long sequence) {
        return new ConversationMessage("conv", "m" + sequence, sequence, 1, 1, 1,
                "历史 " + sequence, "zh-CN", "old", 0, 1, 1);
    }
    private static ConversationMessage row(String id, String task, int role, int status, long updated) {
        return new ConversationMessage("conv", id, 1, role, status, 1, id, "zh-CN", task, 0, 1, updated);
    }
    private static final class Source implements OverlayConversationSource {
        final List<ConversationRepository.ConversationListener> listeners = new ArrayList<>();
        String cancelled;
        int closedDebugSubscriptions;
        final List<ConversationRepository.DebugTraceListener> debugListeners = new ArrayList<>();
        @Override public AutoCloseable subscribeDebug(ConversationRepository.DebugTraceListener listener,
                Consumer<LauncherHostGateway.Result<AutoCloseable>> receiver) {
            debugListeners.add(listener);
            AutoCloseable handle = () -> closedDebugSubscriptions++;
            receiver.accept(LauncherHostGateway.Result.success(handle)); return handle;
        }
        boolean rejectSubscription, holdPages;
        ConversationPage latestPage = new ConversationPage(List.of(), false);
        ConversationPage olderPage = new ConversationPage(List.of(), false);
        final List<Long> pageCursors = new ArrayList<>();
        final List<Consumer<LauncherHostGateway.Result<ConversationPage>>> pages = new ArrayList<>();
        Consumer<LauncherHostGateway.Result<ConversationOperationResult>> cancelReply;
        @Override public boolean isHostConnected() { return true; }
        @Override public AutoCloseable subscribe(String id, ConversationRepository.ConversationListener listener,
                Consumer<LauncherHostGateway.Result<AutoCloseable>> receiver) {
            listeners.add(listener); AutoCloseable handle = () -> {};
            if (rejectSubscription) listener.onConversationError(id, 1);
            receiver.accept(LauncherHostGateway.Result.success(handle)); return handle;
        }
        @Override public void pageMessages(String id, long before, Consumer<LauncherHostGateway.Result<ConversationPage>> receiver) {
            pageCursors.add(before); pages.add(receiver);
            if (!holdPages) receiver.accept(LauncherHostGateway.Result.success(before < 0 ? latestPage : olderPage));
        }
        @Override public void messagesAround(String id, long sequence, Consumer<LauncherHostGateway.Result<ConversationPage>> receiver) {
            receiver.accept(LauncherHostGateway.Result.success(new ConversationPage(List.of(), false)));
        }
        @Override public void messagesAfter(String id, long sequence, Consumer<LauncherHostGateway.Result<ConversationPage>> receiver) {
            receiver.accept(LauncherHostGateway.Result.success(new ConversationPage(List.of(), false)));
        }
        @Override public void cancelMessage(String id, String message, String operation,
                Consumer<LauncherHostGateway.Result<ConversationOperationResult>> receiver) {
            cancelled = message; cancelReply = receiver;
        }
        @Override public void submitTextOrAppend(String id, String text, List<String> attachments,
                ConversationDraft draft, String operation, Consumer<LauncherHostGateway.Result<ConversationSubmission>> receiver) {}
    }
}
