package com.matrix.agent.launcher;

import static org.junit.Assert.*;
import static com.matrix.agent.api.handoff.HandoffProtocol.*;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.graphics.Rect;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.matrix.agent.api.conversation.CreateConversationRequest;
import com.matrix.agent.client.MatrixAgent;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Opt-in real-device scenario. Requires connected Host, overlay permission and installed QQ Music. */
@RunWith(AndroidJUnit4.class)
@androidx.test.filters.SdkSuppress(minSdkVersion = 30)
public final class OverlayDeviceTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final UiAutomation ui = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);

    @Test public void realConversationLaunchBubblePanelDraftReturn() throws Exception {
        var serviceInfo = ui.getServiceInfo();
        serviceInfo.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        ui.setServiceInfo(serviceInfo);
        var context = instrumentation.getTargetContext();
        assertTrue("Grant the explicit overlay special access before this device test", Settings.canDrawOverlays(context));
        MatrixAgent client = MatrixAgent.create(context, (agent, state) -> {});
        try {
            var conversation = client.getConversationManager().createConversation(
                    new CreateConversationRequest("悬浮窗真机验收", null), UUID.randomUUID().toString());
            assertNotNull(conversation);
            Activity activity = instrumentation.startActivitySync(new Intent(context, LauncherActivity.class)
                    .setAction(ACTION_OPEN_CONVERSATION).putExtra(EXTRA_CONVERSATION_ID, conversation.conversationId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            await(() -> ((LauncherApplication) activity.getApplication()).hostGateway().isConnected(), 8000);
            SystemClock.sleep(1000); // Let the real Binder registration and initial snapshot finish.
            instrumentation.runOnMainSync(() -> {
                ((EditText) activity.findViewById(R.id.conversation_input)).setText("打开QQ音乐");
                activity.findViewById(R.id.conversation_voice).performClick();
            });
            await(() -> findDescription("Agent ") != null, 45_000);
            screenshot("01-bubble");
            Rect initialBubble = rectangle(findDescription("Agent "));
            drag(initialBubble.centerX(), initialBubble.centerY(), 60, initialBubble.centerY() + 80);
            await(() -> rectangle(findDescription("Agent ")).left < 100, 3000);
            tap(findDescription("Agent "));
            await(() -> findText("返回 Agent") != null, 5000);
            await(() -> overlayMessages().stream().anyMatch(m -> m.role() == 0 && "打开QQ音乐".equals(m.text())), 5000);
            screenshot("02a-panel-before-drag");
            Rect panelHeader = rectangle(findDescription("移动任务小窗"));
            drag(panelHeader.centerX(), panelHeader.centerY(), panelHeader.centerX(), panelHeader.centerY() - 180);
            await(() -> rectangle(findDescription("移动任务小窗")).top < panelHeader.top - 80, 3000);
            screenshot("02-panel");
            await(() -> findText("可输入补充信息") != null, 30_000);
            AccessibilityNodeInfo input = findText("补充信息或开始新任务");
            assertNotNull(input); tap(input);
            await(() -> findText("可补充信息；Agent 会继续当前操作") != null, 5000);
            await(() -> ui.getWindows().stream().anyMatch(w -> w.getType()
                    == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD), 5000);
            android.os.Bundle arguments = new android.os.Bundle();
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "这是保留的小窗草稿");
            assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments));
            SystemClock.sleep(500);
            Rect editingHeader = rectangle(findDescription("移动任务小窗"));
            drag(editingHeader.centerX(), editingHeader.centerY(), editingHeader.centerX() + 90, editingHeader.centerY());
            await(() -> rectangle(findDescription("移动任务小窗")).left > editingHeader.left + 10, 3000);
            assertNotNull(findText("这是保留的小窗草稿"));
            assertTrue("dragging the title must keep the real IME open", ui.getWindows().stream().anyMatch(w ->
                    w.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD));
            screenshot("03-editing");
            java.util.concurrent.atomic.AtomicReference<String> draftText = new java.util.concurrent.atomic.AtomicReference<>();
            instrumentation.runOnMainSync(() -> draftText.set(((LauncherApplication) activity.getApplication())
                    .overlay().drafts().get(conversation.conversationId).text()));
            assertEquals("这是保留的小窗草稿", draftText.get());
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            await(() -> findText("返回 Agent") != null && ui.getWindows().stream().noneMatch(w -> w.getType()
                    == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD), 3000);
            SystemClock.sleep(300); // Wait for the IME resize animation before comparing positions.
            Rect rememberedHeader = rectangle(findDescription("移动任务小窗"));
            tap(findText("收起"));
            await(() -> findDescription("Agent ") != null, 3000);
            SystemClock.sleep(500);
            tap(findDescription("Agent "));
            SystemClock.sleep(500);
            Rect reopenedHeader = rectangle(findDescription("移动任务小窗"));
            assertEquals("reopening keeps the panel's horizontal position", rememberedHeader.left, reopenedHeader.left);
            assertEquals("reopening keeps the panel's vertical position", rememberedHeader.top, reopenedHeader.top);
            await(() -> overlayMessages().stream().anyMatch(m -> m.role() == 1), 30_000);
            var persisted = client.getConversationManager().getMessages(conversation.conversationId, -1, 30);
            assertNotNull(persisted);
            await(() -> persisted.messages.stream().allMatch(expected -> overlayMessages().stream().anyMatch(actual ->
                    actual.messageId().equals(expected.messageId) && actual.text().equals(expected.text)
                    && actual.role() == expected.role && actual.status() == expected.status)), 5000);
            if (BuildConfig.MATRIX_DEBUG_TRACE_UI) {
                await(() -> overlayMessages().stream().anyMatch(m -> !m.debugTraces().isEmpty()), 5000);
                var processTitle = find(node -> node.getText() != null && node.getText().toString().contains("思考与工具"));
                assertNotNull(processTitle);
                tap(processTitle);
                await(() -> find(node -> node.getContentDescription() != null
                        && node.getContentDescription().toString().startsWith("思考与工具")
                        && node.getContentDescription().toString().endsWith("点击收起详情")) != null, 3000);
                screenshot("03e-process-expanded");
                tap(find(node -> node.getContentDescription() != null
                        && node.getContentDescription().toString().startsWith("思考与工具")
                        && node.getContentDescription().toString().endsWith("点击收起详情")));
            }
            screenshot("03b-reopened");
            await(() -> findText("这是保留的小窗草稿") != null, 3000);
            String rotationPolicy = shell("wm fixed-to-user-rotation").strip();
            String userRotation = shell("wm user-rotation").strip();
            assertTrue(java.util.Set.of("default", "enabled", "disabled", "enabled_if_no_auto_rotation").contains(rotationPolicy));
            assertTrue(userRotation.matches("free|lock [0-3]"));
            try {
                shell("wm fixed-to-user-rotation enabled");
                assertTrue(ui.setRotation(UiAutomation.ROTATION_FREEZE_90));
                SystemClock.sleep(1000);
                Rect display = context.getSystemService(android.view.WindowManager.class).getMaximumWindowMetrics().getBounds();
                assertTrue("rotation check requires an actual landscape display", display.width() > display.height());
                Rect send = rectangle(findText("发送"));
                assertTrue("send action must remain within display after rotation", display.contains(send));
                assertNotNull(findText("这是保留的小窗草稿"));
                assertOverlayViewFullyVisible("发送");
                assertOverlayViewFullyVisible("这是保留的小窗草稿");
                assertNotNull(findText("更多"));
                screenshot("03c-rotation");
                tap(findText("更多"));
                await(() -> findText("返回 Agent") != null && findText("取消任务") != null, 3000);
                screenshot("03d-compact-actions");
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            } finally {
                ui.setRotation(UiAutomation.ROTATION_UNFREEZE);
                shell("wm fixed-to-user-rotation " + rotationPolicy);
                shell("wm user-rotation " + userRotation);
            }
            SystemClock.sleep(1000);
            tap(findText("返回 Agent"));
            await(() -> findText("恢复小窗草稿") != null, 5000);
            assertNull(findText("返回 Agent"));
            screenshot("04-return-draft");
        } finally { client.release(); }
    }

    @Test public void realExistingAppSearchUsesProductionProviderHandoff() throws Exception {
        var info = ui.getServiceInfo();
        info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        ui.setServiceInfo(info);
        var context = instrumentation.getTargetContext();
        MatrixAgent client = MatrixAgent.create(context, (agent, state) -> {});
        try {
            var conversation = client.getConversationManager().createConversation(
                    new CreateConversationRequest("已有应用搜索交接验收", null), UUID.randomUUID().toString());
            assertNotNull(conversation);
            Activity activity = instrumentation.startActivitySync(new Intent(context, LauncherActivity.class)
                    .setAction(ACTION_OPEN_CONVERSATION).putExtra(EXTRA_CONVERSATION_ID, conversation.conversationId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            await(() -> ((LauncherApplication) activity.getApplication()).hostGateway().isConnected(), 8000);
            SystemClock.sleep(1000);
            context.startActivity(new Intent().setClassName("com.tencent.qqmusic",
                    "com.tencent.qqmusic.activity.AppStarterActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            await(() -> {
                var root = ui.getRootInActiveWindow();
                return root != null && "com.tencent.qqmusic".contentEquals(root.getPackageName());
            }, 5000);
            // The production QQ workflow searches first and requires a separate confirmation to
            // play. Deliberately stop at the candidate answer; no selection/playback is submitted.
            var receipt = client.getConversationManager().sendText(new com.matrix.agent.api.conversation.SendTextRequest(
                    conversation.conversationId, "播放李健的传奇", "zh-CN"), UUID.randomUUID().toString());
            assertNotNull(receipt); assertTrue(receipt.isAccepted());
            await(() -> findDescription("Agent ") != null, 15_000);
            java.util.concurrent.atomic.AtomicReference<String> lastRead = new java.util.concurrent.atomic.AtomicReference<>("no page");
            try { await(() -> {
                var page = client.getConversationManager().getMessages(conversation.conversationId, -1, 30);
                if (page == null) return false;
                lastRead.set(page.messages.stream().map(message -> "role=" + message.role + " status=" + message.status
                        + " sameTask=" + receipt.conversationTaskId.equals(message.conversationTaskId)
                        + " traces=" + message.executionTraces.stream().map(trace -> trace.capabilityId + ":" + trace.outcome).toList()
                        + " text=" + message.text).toList().toString());
                return page.messages.stream().filter(message -> receipt.userMessageId.equals(message.messageId))
                        .flatMap(message -> message.executionTraces.stream()).anyMatch(trace ->
                                "media.qqmusic.search_songs".equals(trace.capabilityId)
                                && "SUCCESS".equals(trace.outcome) && "VERIFIED".equals(trace.verificationState));
            }, 20_000); }
            catch (AssertionError failed) { throw new AssertionError("Search facts: " + lastRead.get(), failed); }
            screenshot("05-existing-app-search");
            instrumentation.runOnMainSync(() -> ((LauncherApplication) activity.getApplication()).overlay().dismiss());
        } finally { client.release(); }
    }
    private java.util.List<com.matrix.agent.launcher.presentation.ConversationViewModel.UiMessage> overlayMessages() {
        var result = new java.util.ArrayList<com.matrix.agent.launcher.presentation.ConversationViewModel.UiMessage>();
        instrumentation.runOnMainSync(() -> {
            for (var root : android.view.inspector.WindowInspector.getGlobalWindowViews()) {
                if (root.getLayoutParams() instanceof android.view.WindowManager.LayoutParams params
                        && params.type == android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) {
                    readTimeline(root, result);
                }
            }
        });
        return result;
    }
    private void readTimeline(android.view.View node,
            java.util.List<com.matrix.agent.launcher.presentation.ConversationViewModel.UiMessage> result) {
        if (node instanceof android.widget.ListView list) {
            for (int i = 0; i < list.getAdapter().getCount(); i++) {
                if (list.getAdapter().getItem(i) instanceof com.matrix.agent.launcher.presentation.ConversationViewModel.UiMessage message)
                    result.add(message);
            }
            return;
        }
        if (node instanceof android.view.ViewGroup group) for (int i = 0; i < group.getChildCount(); i++)
            readTimeline(group.getChildAt(i), result);
    }

    private void assertOverlayViewFullyVisible(String text) {
        instrumentation.runOnMainSync(() -> {
            android.view.View match = null;
            for (var root : android.view.inspector.WindowInspector.getGlobalWindowViews()) {
                if (root.getLayoutParams() instanceof android.view.WindowManager.LayoutParams params
                        && params.type == android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) {
                    match = findNativeText(root, text);
                    if (match != null) break;
                }
            }
            assertNotNull("missing native overlay control: " + text, match);
            Rect visible = new Rect();
            assertTrue(match.getGlobalVisibleRect(visible));
            assertEquals("control must not be clipped horizontally: " + text, match.getWidth(), visible.width());
            assertEquals("control must not be clipped vertically: " + text, match.getHeight(), visible.height());
        });
    }
    private android.view.View findNativeText(android.view.View node, String text) {
        if (node.isShown() && node instanceof android.widget.TextView label && text.contentEquals(label.getText())) return node;
        if (node instanceof android.view.ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                var found = findNativeText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findDescription(String prefix) {
        return find(node -> node.getContentDescription() != null && node.getContentDescription().toString().startsWith(prefix));
    }
    private AccessibilityNodeInfo findText(String text) {
        return find(node -> node.getText() != null && text.contentEquals(node.getText()));
    }
    private AccessibilityNodeInfo find(java.util.function.Predicate<AccessibilityNodeInfo> predicate) {
        for (var window : ui.getWindows()) {
            var root = window.getRoot();
            if (root == null) continue;
            var found = visit(root, predicate);
            if (found != null) return found;
        }
        return null;
    }
    private AccessibilityNodeInfo visit(AccessibilityNodeInfo node, java.util.function.Predicate<AccessibilityNodeInfo> predicate) {
        if (node.isVisibleToUser() && predicate.test(node)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            var child = node.getChild(i);
            if (child == null) continue;
            var found = visit(child, predicate);
            if (found != null) return found;
        }
        return null;
    }
    private void tap(AccessibilityNodeInfo node) {
        assertNotNull(node);
        Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
        long time = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, bounds.centerX(), bounds.centerY(), 0);
        MotionEvent up = MotionEvent.obtain(time, time + 50, MotionEvent.ACTION_UP, bounds.centerX(), bounds.centerY(), 0);
        ui.injectInputEvent(down, true); ui.injectInputEvent(up, true); down.recycle(); up.recycle();
    }
    private Rect rectangle(AccessibilityNodeInfo node) {
        assertNotNull(node);
        Rect result = new Rect(); node.getBoundsInScreen(result); return result;
    }
    private void drag(float startX, float startY, float endX, float endY) {
        long start = SystemClock.uptimeMillis();
        for (int i = 0; i <= 10; i++) {
            int action = i == 0 ? MotionEvent.ACTION_DOWN : i == 10 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
            MotionEvent event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action,
                    startX + (endX - startX) * i / 10, startY + (endY - startY) * i / 10, 0);
            ui.injectInputEvent(event, true); event.recycle(); SystemClock.sleep(20);
        }
    }
    private void await(BooleanSupplier condition, long timeout) {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition.getAsBoolean()) return;
            SystemClock.sleep(100);
        }
        fail("Device condition timed out after " + timeout + " ms");
    }
    private void screenshot(String name) throws Exception {
        File directory = new File(instrumentation.getTargetContext().getExternalFilesDir(null), "overlay-verification");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (var output = new FileOutputStream(new File(directory, name + ".png"))) {
            ui.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
        }
    }
    private String shell(String command) throws Exception {
        try (var descriptor = ui.executeShellCommand(command);
                var input = new java.io.FileInputStream(descriptor.getFileDescriptor())) {
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
