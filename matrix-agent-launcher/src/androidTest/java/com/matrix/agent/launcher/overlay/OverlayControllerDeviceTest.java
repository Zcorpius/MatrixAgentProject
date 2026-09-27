package com.matrix.agent.launcher.overlay;

import static org.junit.Assert.*;
import static com.matrix.agent.api.handoff.HandoffProtocol.*;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.matrix.agent.api.conversation.*;
import com.matrix.agent.api.handoff.*;
import com.matrix.agent.launcher.LauncherApplication;
import com.matrix.agent.launcher.data.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/** Real WindowManager with a controllable conversation port: deterministic fault and lifecycle cases. */
@RunWith(AndroidJUnit4.class)
@androidx.test.filters.SdkSuppress(minSdkVersion = 29)
public final class OverlayControllerDeviceTest {
    private final android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private OverlayController controller;
    private Source source;
    private com.matrix.agent.diagnostics.HandoffDiagnostics diagnostics;
    @Before public void create() {
        main(() -> {
            var app = (LauncherApplication) instrumentation.getTargetContext().getApplicationContext();
            app.overlay().dismiss();
            source = new Source();
            diagnostics = new com.matrix.agent.diagnostics.HandoffDiagnostics();
            controller = new OverlayController(app, app.hostGateway(), source, diagnostics);
            controller.connectionChanged(true);
        });
    }
    @After public void close() { main(controller::close); }
    @Test public void samePageInteractionHasNoOverlayAndManualDepartureCannotReveal() throws Exception {
        main(() -> controller.conversationPageVisible("a"));
        var initial = request("a", CREATE_OR_REBIND, INTERACT_EXISTING_APP, 1200);
        assertEquals(PRESENTED_IN_LAUNCHER, prepare(initial).result());
        main(() -> controller.conversationPageVisible(null));
        var decision = controller.fastDecision(request("a", REUSE, INTERACT_EXISTING_APP, 50));
        assertEquals(REUSE_STATE_STALE, decision.reason());
    }
    @Test public void failedLaunchCannotRevealHiddenPreparationOnLateDeparture() throws Exception {
        main(() -> controller.conversationPageVisible("a"));
        var initial = request("a", CREATE_OR_REBIND, LAUNCH_ACTIVITY, 1200);
        assertEquals(OVERLAY_PREPARED, prepare(initial).result());
        main(() -> {
            controller.launchFinished(initial.handoffRequestId(), DISPATCH_FAILED, SystemClock.elapsedRealtime());
            controller.conversationPageVisible(null);
        });
        assertEquals(REUSE_STATE_STALE, controller.fastDecision(request("a", REUSE, LAUNCH_ACTIVITY, 50)).reason());
    }
    @Test public void launchAndDepartureWorkInEitherOrder() throws Exception {
        for (boolean dispatchFirst : new boolean[]{true, false}) {
            main(() -> controller.conversationPageVisible("a"));
            var initial = request("a", CREATE_OR_REBIND, LAUNCH_ACTIVITY, 1200);
            assertEquals(OVERLAY_PREPARED, prepare(initial).result());
            main(() -> {
                if (dispatchFirst) controller.launchFinished(initial.handoffRequestId(), DISPATCHED, SystemClock.elapsedRealtime());
                controller.conversationPageVisible(null);
                if (!dispatchFirst) controller.launchFinished(initial.handoffRequestId(), DISPATCHED, SystemClock.elapsedRealtime());
            });
            assertEquals(OVERLAY_READY, controller.fastDecision(request("a", REUSE, INTERACT_EXISTING_APP, 50)).result());
        }
    }
    @Test public void stuckOwnerQueryTimesOutOnceAndLateAnswerCannotRebind() throws Exception {
        assertEquals(OVERLAY_READY, prepare(request("a", CREATE_OR_REBIND, INTERACT_EXISTING_APP, 1200)).result());
        main(() -> source.hang = true);
        var second = prepare(request("b", CREATE_OR_REBIND, INTERACT_EXISTING_APP, 250));
        assertEquals(OVERLAY_UNAVAILABLE, second.result()); assertEquals(OWNER_STATE_UNAVAILABLE, second.reason());
        assertEquals(1, source.hangingQueries);
        assertEquals(OVERLAY_UNAVAILABLE, prepare(request("b", CREATE_OR_REBIND, INTERACT_EXISTING_APP, 250)).result());
        assertEquals(1, source.hangingQueries);
        main(() -> source.late.accept(LauncherHostGateway.Result.success(new ConversationPage(List.of(source.row("a", 2)), false))));
        assertEquals(OVERLAY_READY, controller.fastDecision(request("a", REUSE, INTERACT_EXISTING_APP, 50)).result());
        assertEquals(REUSE_STATE_STALE, controller.fastDecision(request("b", REUSE, INTERACT_EXISTING_APP, 50)).reason());
    }
    @Test public void activeOwnerIsBusyAndClosedRuntimeStaysDismissed() throws Exception {
        assertEquals(OVERLAY_READY, prepare(request("a", CREATE_OR_REBIND, INTERACT_EXISTING_APP, 1200)).result());
        assertEquals(OVERLAY_BUSY, prepare(request("b", CREATE_OR_REBIND, INTERACT_EXISTING_APP, 1200)).result());
        main(controller::dismiss);
        assertEquals(USER_DISMISSED, controller.fastDecision(request("a", REUSE, INTERACT_EXISTING_APP, 50)).result());
    }
    @Test public void reuseDoesNotWaitForMainThread() throws Exception {
        assertEquals(OVERLAY_READY, prepare(request("a", CREATE_OR_REBIND, INTERACT_EXISTING_APP, 1200)).result());
        CountDownLatch busy = new CountDownLatch(1), release = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            busy.countDown();
            try { release.await(1, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        });
        assertTrue(busy.await(1, TimeUnit.SECONDS));
        try {
            long started = SystemClock.elapsedRealtime();
            assertEquals(OVERLAY_READY, controller.fastDecision(request("a", REUSE, INTERACT_EXISTING_APP, 50)).result());
            assertTrue(SystemClock.elapsedRealtime() - started < 50);
        } finally { release.countDown(); }
    }
    @Test public void automationGatesNewInputButPreservesAnExistingEditor() throws Exception {
        var context = instrumentation.getTargetContext();
        var target = new android.content.Intent().setClassName("com.tencent.qqmusic",
                "com.tencent.qqmusic.activity.AppStarterActivity");
        context.startActivity(target.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        SystemClock.sleep(500);
        assertEquals(OVERLAY_READY, prepare(request("a", CREATE_OR_REBIND, INTERACT_EXISTING_APP, 1200)).result());
        main(() -> controller.activity(new ExternalUiActivitySnapshot(9, 0, 1, AUTOMATION)));
        main(() -> awaitView("Agent ", true).performClick());
        var input = (android.widget.EditText) awaitView("补充信息或开始新任务", false);
        main(input::performClick);
        assertNotNull(awaitView("正在操作应用，完成当前步骤后可输入", false));
        main(() -> controller.activity(new ExternalUiActivitySnapshot(9, 0, 2, IDLE)));
        main(input::performClick);
        assertNotNull(awaitView("可补充信息；Agent 会继续当前操作", false));
        main(() -> {
            input.setText("编辑不中断");
            var handle = awaitView("移动任务小窗", true);
            dragView(handle, 0, -60);
            var params = (android.view.WindowManager.LayoutParams) handle.getRootView().getLayoutParams();
            assertEquals(0, params.flags & android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            assertTrue(input.hasFocus());
        });
        main(() -> controller.activity(new ExternalUiActivitySnapshot(9, 0, 3, AUTOMATION)));
        assertEquals(OVERLAY_READY, controller.fastDecision(request("a", REUSE, INTERACT_EXISTING_APP, 50)).result());
        assertNotNull(awaitView("编辑不中断", false));
        assertNotNull(awaitView("可补充信息；Agent 会继续当前操作", false));
        assertNotNull(awaitView("返回 Agent", false));
    }
    @Test public void expandedPanelDragsWithoutRecentringAndKeepsSeparateBubblePosition() throws Exception {
        assertEquals(OVERLAY_READY, prepare(request("a", CREATE_OR_REBIND, INTERACT_EXISTING_APP, 1200)).result());
        var bubble = awaitView("Agent ", true);
        main(() -> {
            var params = (android.view.WindowManager.LayoutParams) bubble.getRootView().getLayoutParams();
            int bubbleX = params.x, bubbleY = params.y;
            bubble.performClick();
            var handle = awaitView("移动任务小窗", true);
            int startY = params.y;
            dragView(handle, 0, -80);
            int panelX = params.x, panelY = params.y;
            assertTrue("expanded window must move with its header", panelY < startY);
            controller.draftChanged("拖动后仍保留的草稿");
            assertEquals(panelX, params.x); assertEquals(panelY, params.y);
            awaitView("收起", false).performClick();
            assertEquals(bubbleX, params.x); assertEquals(bubbleY, params.y);
            bubble.performClick();
            assertEquals(panelX, params.x); assertEquals(panelY, params.y);
            assertNotNull(awaitView("拖动后仍保留的草稿", false));
            assertNotEquals(0, params.flags & android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
            awaitView("取消任务", false).performClick();
            assertEquals(1, source.cancellations);
            assertEquals(panelX, params.x); assertEquals(panelY, params.y);
        });
    }
    @Test public void draggingHeaderBeyondEdgesKeepsPanelOnScreen() throws Exception {
        assertEquals(OVERLAY_READY, prepare(request("a", CREATE_OR_REBIND, INTERACT_EXISTING_APP, 1200)).result());
        main(() -> {
            awaitView("Agent ", true).performClick();
            var handle = awaitView("移动任务小窗", true);
            var params = (android.view.WindowManager.LayoutParams) handle.getRootView().getLayoutParams();
            dragView(handle, -10000, -10000);
            assertTrue(params.x >= 0); assertTrue(params.y >= 0);
            int edge = Math.round(8 * handle.getResources().getDisplayMetrics().density);
            assertEquals("negative drag must clamp, not reset to center", edge, params.x);
            dragView(handle, 10000, 10000);
            var metrics = new android.util.DisplayMetrics();
            instrumentation.getTargetContext().getSystemService(android.hardware.display.DisplayManager.class)
                    .getDisplay(android.view.Display.DEFAULT_DISPLAY).getRealMetrics(metrics);
            assertTrue(params.x + params.width <= metrics.widthPixels);
            assertTrue(params.y + params.height <= metrics.heightPixels);
        });
    }
    private void dragView(android.view.View handle, float dx, float dy) {
        long down = SystemClock.uptimeMillis();
        int[] actions = {android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE,
                android.view.MotionEvent.ACTION_UP};
        for (int i = 0; i < actions.length; i++) {
            var event = android.view.MotionEvent.obtain(down, down + i * 16, actions[i],
                    12 + (i == 0 ? 0 : dx), 12 + (i == 0 ? 0 : dy), 0);
            try { assertTrue(handle.dispatchTouchEvent(event)); } finally { event.recycle(); }
        }
    }

    private android.view.View awaitView(String text, boolean description) {
        AtomicReference<android.view.View> result = new AtomicReference<>();
        Runnable lookup = () -> {
            for (var root : android.view.inspector.WindowInspector.getGlobalWindowViews()) {
                var found = findView(root, text, description);
                if (found != null) { result.set(found); return; }
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) lookup.run();
        else main(lookup);
        assertNotNull("Missing overlay view: " + text, result.get());
        return result.get();
    }
    private android.view.View findView(android.view.View node, String text, boolean description) {
        CharSequence value = description ? node.getContentDescription()
                : node instanceof android.widget.TextView label ? label.getText() : null;
        if (!description && node instanceof android.widget.EditText input && input.length() == 0) value = input.getHint();
        if (node.isShown() && value != null && (description ? value.toString().startsWith(text) : text.contentEquals(value))) return node;
        if (node instanceof android.view.ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                var found = findView(group.getChildAt(i), text, description);
                if (found != null) return found;
            }
        }
        return null;
    }

    @Test public void permissionDenialAndRevocationReleaseWindowWithoutCancellingTask() throws Exception {
        try {
            shell("cmd appops set com.matrix.agent.launcher SYSTEM_ALERT_WINDOW deny");
            var unavailable = prepare(request("a", CREATE_OR_REBIND, INTERACT_EXISTING_APP, 1200));
            assertEquals(OVERLAY_UNAVAILABLE, unavailable.result());
            assertEquals(PERMISSION_DENIED, unavailable.reason());
            shell("cmd appops set com.matrix.agent.launcher SYSTEM_ALERT_WINDOW allow");
            assertEquals(OVERLAY_READY, prepare(request("a", CREATE_OR_REBIND, INTERACT_EXISTING_APP, 1200)).result());
            shell("cmd appops set com.matrix.agent.launcher SYSTEM_ALERT_WINDOW deny");
            SystemClock.sleep(200);
            assertEquals(REUSE_STATE_STALE, controller.fastDecision(request("a", REUSE, INTERACT_EXISTING_APP, 50)).reason());
            assertEquals(0, source.cancellations);
        } finally { shell("cmd appops set com.matrix.agent.launcher SYSTEM_ALERT_WINDOW allow"); }
    }
    @Test public void lockScreenClearsWindowAndOldLaunchCannotResurrectIt() throws Exception {
        var original = request("a", CREATE_OR_REBIND, INTERACT_EXISTING_APP, 1200);
        assertEquals(OVERLAY_READY, prepare(original).result());
        try {
            shell("input keyevent KEYCODE_SLEEP");
            awaitCondition(() -> controller.fastDecision(request("a", REUSE, INTERACT_EXISTING_APP, 50)).reason() == REUSE_STATE_STALE);
            assertEquals(REUSE_STATE_STALE, controller.fastDecision(request("a", REUSE, INTERACT_EXISTING_APP, 50)).reason());
            main(() -> controller.launchFinished(original.handoffRequestId(), DISPATCHED, SystemClock.elapsedRealtime()));
            assertEquals(REUSE_STATE_STALE, controller.fastDecision(request("a", REUSE, INTERACT_EXISTING_APP, 50)).reason());
        } finally {
            shell("input keyevent KEYCODE_WAKEUP");
            var context = instrumentation.getTargetContext();
            awaitCondition(() -> context.getSystemService(android.os.PowerManager.class).isInteractive());
            shell("wm dismiss-keyguard");
            awaitCondition(() -> !context.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked());
        }
    }
    private void awaitCondition(java.util.function.BooleanSupplier condition) {
        long end = SystemClock.elapsedRealtime() + 5000;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(50);
        assertTrue("system transition did not complete", condition.getAsBoolean());
    }
    private void shell(String command) throws Exception {
        var ui = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        try (var descriptor = ui.executeShellCommand(command);
                var stream = new java.io.FileInputStream(descriptor.getFileDescriptor())) {
            stream.readAllBytes();
        }
    }

    @Test public void existingHiddenWindowCanPrepareLaunchWithoutWindowRecreation() throws Exception {
        assertEquals(OVERLAY_READY, prepare(request("a", CREATE_OR_REBIND, INTERACT_EXISTING_APP, 1200)).result());
        main(() -> controller.conversationPageVisible("a"));
        var next = request("a", REUSE, LAUNCH_ACTIVITY, 50);
        assertEquals(OVERLAY_PREPARED, controller.fastDecision(next).result());
        main(() -> {
            controller.acknowledged(next, true);
            controller.launchFinished(next.handoffRequestId(), DISPATCHED, SystemClock.elapsedRealtime());
            controller.conversationPageVisible(null);
        });
        assertEquals(OVERLAY_READY, controller.fastDecision(request("a", REUSE, INTERACT_EXISTING_APP, 50)).result());
    }

    @Test public void candidateCannotAcceptTouchBeforeOwnershipIsCommitted() throws Exception {
        var request = request("a", CREATE_OR_REBIND, INTERACT_EXISTING_APP, 1200);
        CompletableFuture<HandoffClient.Decision> response = new CompletableFuture<>();
        main(() -> controller.prepare(request, response::complete));
        assertEquals(OVERLAY_READY, response.get(2, TimeUnit.SECONDS).result());
        var bubble = awaitView("Agent ", true);
        main(() -> {
            var root = bubble.getRootView();
            var params = (android.view.WindowManager.LayoutParams) root.getLayoutParams();
            assertNotEquals(0, params.flags & android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            assertFalse(bubble.performClick());
            controller.acknowledged(request, true);
            params = (android.view.WindowManager.LayoutParams) root.getLayoutParams();
            assertEquals(0, params.flags & android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            assertTrue(bubble.performClick());
        });
        assertNotNull(awaitView("返回 Agent", false));
    }

    @Test public void replacingTerminalOwnerShowsOneWindowAndRejectedAckRestoresIt() throws Exception {
        assertEquals(OVERLAY_READY, prepare(request("a", CREATE_OR_REBIND, INTERACT_EXISTING_APP, 1200)).result());
        main(() -> source.terminalOwners.add("a"));
        var replacement = request("b", CREATE_OR_REBIND, INTERACT_EXISTING_APP, 1200);
        CompletableFuture<HandoffClient.Decision> response = new CompletableFuture<>();
        main(() -> controller.prepare(replacement, response::complete));
        assertEquals(OVERLAY_READY, response.get(2, TimeUnit.SECONDS).result());
        main(() -> {
            long visibleEntries = android.view.inspector.WindowInspector.getGlobalWindowViews().stream()
                    .filter(root -> findView(root, "Agent ", true) != null).count();
            assertEquals(1, visibleEntries);
            controller.acknowledged(replacement, false);
            visibleEntries = android.view.inspector.WindowInspector.getGlobalWindowViews().stream()
                    .filter(root -> findView(root, "Agent ", true) != null).count();
            assertEquals(1, visibleEntries);
        });
        assertEquals(OVERLAY_READY, controller.fastDecision(request("a", REUSE, INTERACT_EXISTING_APP, 50)).result());
        assertEquals(REUSE_STATE_STALE, controller.fastDecision(request("b", REUSE, INTERACT_EXISTING_APP, 50)).reason());
    }

    @Test public void suppressedCommittedWindowSurvivesFailedReuseLaunch() throws Exception {
        assertSuppressedOwnerSurvives(false, false);
    }
    @Test public void suppressedCommittedWindowSurvivesDisconnectAndLateDispatch() throws Exception {
        assertSuppressedOwnerSurvives(true, false);
    }
    @Test public void suppressedCommittedWindowSurvivesRejectedReuseAck() throws Exception {
        assertSuppressedOwnerSurvives(false, true);
    }
    private void assertSuppressedOwnerSurvives(boolean disconnect, boolean rejectAck) throws Exception {
        assertEquals(OVERLAY_READY, prepare(request("a", CREATE_OR_REBIND, INTERACT_EXISTING_APP, 1200)).result());
        var originalRoot = awaitView("Agent ", true).getRootView();
        main(() -> {
            controller.drafts().set("a", "保留的草稿");
            controller.conversationPageVisible("a");
            var failed = request("a", REUSE, LAUNCH_ACTIVITY, 50);
            assertEquals(OVERLAY_PREPARED, controller.fastDecision(failed).result());
            controller.acknowledged(failed, !rejectAck);
            if (disconnect) {
                controller.connectionChanged(false);
                controller.connectionChanged(true);
            } else if (!rejectAck) {
                controller.launchFinished(failed.handoffRequestId(), DISPATCH_FAILED, SystemClock.elapsedRealtime());
            }
            assertTrue(originalRoot.isAttachedToWindow());
            assertEquals(disconnect ? 2 : 1, source.subscriptions);
            assertEquals(disconnect ? 1 : 0, source.closedSubscriptions);
            assertEquals("保留的草稿", controller.drafts().get("a").text());
            // Late dispatch and manual departure cannot resurrect an invalidated ticket.
            controller.launchFinished(failed.handoffRequestId(), DISPATCHED, SystemClock.elapsedRealtime());
            controller.conversationPageVisible(null);
            assertEquals(android.view.View.INVISIBLE, originalRoot.getVisibility());
            controller.conversationPageVisible("a");
            var next = request("a", REUSE, LAUNCH_ACTIVITY, 50);
            assertEquals(OVERLAY_PREPARED, controller.fastDecision(next).result());
            controller.acknowledged(next, true);
            controller.launchFinished(next.handoffRequestId(), DISPATCHED, SystemClock.elapsedRealtime());
            controller.conversationPageVisible(null);
            assertEquals(android.view.View.VISIBLE, originalRoot.getVisibility());
            assertEquals(disconnect ? 2 : 1, source.subscriptions);
            assertEquals(disconnect ? 1 : 0, source.closedSubscriptions);
        });
        assertEquals(OVERLAY_READY, controller.fastDecision(request("a", REUSE, INTERACT_EXISTING_APP, 50)).result());
    }
    @Test public void disconnectDisposesNeverRevealedPreparationAndSubscription() throws Exception {
        main(() -> controller.conversationPageVisible("a"));
        var initial = request("a", CREATE_OR_REBIND, LAUNCH_ACTIVITY, 1200);
        assertEquals(OVERLAY_PREPARED, prepare(initial).result());
        main(() -> {
            controller.connectionChanged(false);
            assertEquals(1, source.closedSubscriptions);
            controller.connectionChanged(true);
            controller.launchFinished(initial.handoffRequestId(), DISPATCHED, SystemClock.elapsedRealtime());
            controller.conversationPageVisible(null);
        });
        assertEquals(REUSE_STATE_STALE, controller.fastDecision(request("a", REUSE, LAUNCH_ACTIVITY, 50)).reason());
    }

    @Test public void hiddenAttachIsNotCountedAsFirstDrawAndRevealIsCountedOnce() throws Exception {
        main(() -> controller.conversationPageVisible("a"));
        var initial = request("a", CREATE_OR_REBIND, LAUNCH_ACTIVITY, 1200);
        assertEquals(OVERLAY_PREPARED, prepare(initial).result());
        instrumentation.waitForIdleSync();
        assertEquals(1, diagnostics.snapshot().events().stream().filter(e -> e.stage() ==
                com.matrix.agent.diagnostics.HandoffDiagnostics.Stage.WINDOW_ATTACH).count());
        assertEquals(0, firstDrawCount());
        main(() -> {
            controller.launchFinished(initial.handoffRequestId(), DISPATCHED, SystemClock.elapsedRealtime());
            controller.conversationPageVisible(null);
        });
        long deadline = SystemClock.elapsedRealtime() + 2000;
        while (firstDrawCount() == 0 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(10);
        assertEquals(1, firstDrawCount());
        main(() -> {
            controller.conversationPageVisible("a");
            var next = request("a", REUSE, LAUNCH_ACTIVITY, 50);
            assertEquals(OVERLAY_PREPARED, controller.fastDecision(next).result());
            controller.acknowledged(next, true);
            controller.launchFinished(next.handoffRequestId(), DISPATCHED, SystemClock.elapsedRealtime());
            controller.conversationPageVisible(null);
        });
        instrumentation.waitForIdleSync();
        assertEquals(1, firstDrawCount());
    }
    private long firstDrawCount() {
        return diagnostics.snapshot().events().stream().filter(e -> e.stage() ==
                com.matrix.agent.diagnostics.HandoffDiagnostics.Stage.FIRST_DRAW).count();
    }

    @Test public void fullConversationPagesAndLiveRepliesShareTheFullscreenProjection() throws Exception {
        main(() -> {
            for (int i = 1; i <= 40; i++) source.history.add(new ConversationMessage("a", "history-" + i,
                    i, i % 2, ConversationMessage.STATUS_COMPLETED, 1, "历史对话 " + i,
                    "zh-CN", "old-task", 0, i, i));
            source.history.add(new ConversationMessage("a", "user-a", 41,
                    ConversationMessage.ROLE_USER, ConversationMessage.STATUS_RUNNING, 1,
                    "当前用户问题", "zh-CN", "task-a", 0, 41, 41));
        });
        assertEquals(OVERLAY_READY, prepare(request("a", CREATE_OR_REBIND, INTERACT_EXISTING_APP, 1200, 41)).result());
        main(() -> awaitView("Agent ", true).performClick());
        instrumentation.waitForIdleSync();
        AtomicReference<android.widget.ListView> list = new AtomicReference<>();
        main(() -> {
            var root = awaitView("移动任务小窗", true).getRootView();
            list.set(findConversationList(root)); assertNotNull(list.get());
            assertEquals(30, timeline(list.get()).size());
            assertEquals(source.history.subList(11, 41).stream()
                    .map(com.matrix.agent.launcher.presentation.ConversationViewModel.UiMessage::from).toList(), timeline(list.get()));
            list.get().setSelection(0);
        });
        instrumentation.waitForIdleSync();
        main(() -> awaitView("查看更早消息", false).performClick());
        instrumentation.waitForIdleSync();
        AtomicInteger previousPosition = new AtomicInteger();
        main(() -> {
            assertEquals(source.history.stream()
                    .map(com.matrix.agent.launcher.presentation.ConversationViewModel.UiMessage::from).toList(), timeline(list.get()));
            assertEquals(List.of(-1L, 12L), source.pageCursors);
            previousPosition.set(list.get().getFirstVisiblePosition());
            var answer = new ConversationMessage("a", "answer-a", 42, ConversationMessage.ROLE_ASSISTANT,
                    ConversationMessage.STATUS_COMPLETED, 0, "新回复完整正文", "zh-CN", "task-a", 0, 42, 42);
            source.listeners.get(0).onMessageUpsert(answer);
        });
        instrumentation.waitForIdleSync();
        main(() -> {
            assertEquals(42, timeline(list.get()).size());
            assertEquals("新回复完整正文", timeline(list.get()).get(41).text());
            assertEquals("new answers must not pull a history reader to the bottom",
                    previousPosition.get(), list.get().getFirstVisiblePosition());
        });
    }
    private android.widget.ListView findConversationList(android.view.View node) {
        if (node instanceof android.widget.ListView list) return list;
        if (node instanceof android.view.ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            var list = findConversationList(group.getChildAt(i)); if (list != null) return list;
        }
        return null;
    }
    private List<com.matrix.agent.launcher.presentation.ConversationViewModel.UiMessage> timeline(android.widget.ListView list) {
        List<com.matrix.agent.launcher.presentation.ConversationViewModel.UiMessage> rows = new ArrayList<>();
        for (int i = 0; i < list.getAdapter().getCount(); i++) {
            if (list.getAdapter().getItem(i) instanceof com.matrix.agent.launcher.presentation.ConversationViewModel.UiMessage message)
                rows.add(message);
        }
        return rows;
    }

    private HandoffClient.Decision prepare(ExternalAppHandoffRequest request) throws Exception {
        CompletableFuture<HandoffClient.Decision> result = new CompletableFuture<>();
        main(() -> controller.prepare(request, decision -> {
            controller.acknowledged(request, isPresentationReady(decision.result()));
            result.complete(decision);
        }));
        return result.get(2, TimeUnit.SECONDS);
    }
    private ExternalAppHandoffRequest request(String owner, int mode, int reason, long timeout) {
        return request(owner, mode, reason, timeout, 1);
    }
    private ExternalAppHandoffRequest request(String owner, int mode, int reason, long timeout, long sequence) {
        long now = SystemClock.elapsedRealtime();
        return new ExternalAppHandoffRequest(9, UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "runtime-" + owner, owner, "task-" + owner, "user-" + owner, sequence, "com.tencent.qqmusic",
                reason, mode, now, now + timeout, now + 5000);
    }
    private void main(Runnable action) { instrumentation.runOnMainSync(action); }
    private static final class Source implements OverlayConversationSource {
        private final Handler main = new Handler(Looper.getMainLooper());
        boolean hang; int hangingQueries, cancellations, subscriptions, closedSubscriptions;
        final Set<String> terminalOwners = new HashSet<>();
        final List<ConversationMessage> history = new ArrayList<>();
        final List<Long> pageCursors = new ArrayList<>();
        final List<ConversationRepository.ConversationListener> listeners = new ArrayList<>();
        Consumer<LauncherHostGateway.Result<ConversationPage>> late;
        ConversationMessage row(String id, int status) {
            var saved = history.stream().filter(m -> m.messageId.equals("user-" + id)).findFirst();
            if (saved.isPresent()) return saved.get();
            return new ConversationMessage(id, "user-" + id, 1, 0, status, 1, "test", "zh-CN", "task-" + id, 0, 1, 1);
        }
        @Override public boolean isHostConnected() { return true; }
        @Override public AutoCloseable subscribe(String id, ConversationRepository.ConversationListener listener,
                Consumer<LauncherHostGateway.Result<AutoCloseable>> receiver) {
            subscriptions++; listeners.add(listener);
            AtomicBoolean closed = new AtomicBoolean();
            AutoCloseable handle = () -> { if (closed.compareAndSet(false, true)) closedSubscriptions++; };
            main.post(() -> { listener.onMessageUpsert(row(id, 1)); receiver.accept(LauncherHostGateway.Result.success(handle)); });
            return handle;
        }
        @Override public void pageMessages(String id, long before, Consumer<LauncherHostGateway.Result<ConversationPage>> receiver) {
            pageCursors.add(before);
            var candidates = history.stream().filter(m -> before < 0 || m.sequenceNo < before).toList();
            var page = candidates.subList(Math.max(0, candidates.size() - 30), candidates.size());
            receiver.accept(LauncherHostGateway.Result.success(new ConversationPage(page, candidates.size() > 30)));
        }
        @Override public void messagesAround(String id, long sequence, Consumer<LauncherHostGateway.Result<ConversationPage>> receiver) {
            if (hang) { hangingQueries++; late = receiver; return; }
            main.post(() -> receiver.accept(LauncherHostGateway.Result.success(new ConversationPage(
                    List.of(row(id, terminalOwners.contains(id) ? ConversationMessage.STATUS_COMPLETED : ConversationMessage.STATUS_RUNNING)), false))));
        }
        @Override public void messagesAfter(String id, long sequence, Consumer<LauncherHostGateway.Result<ConversationPage>> receiver) {
            main.post(() -> receiver.accept(LauncherHostGateway.Result.success(new ConversationPage(List.of(), false))));
        }
        @Override public void cancelMessage(String id, String message, String operation,
                Consumer<LauncherHostGateway.Result<ConversationOperationResult>> receiver) { cancellations++; }
        @Override public void submitTextOrAppend(String id, String text, List<String> attachments,
                ConversationDraft draft, String operation, Consumer<LauncherHostGateway.Result<ConversationSubmission>> receiver) {}
    }
}
